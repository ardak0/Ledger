package com.ardakeles.ledger.service;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountNotFoundException;
import com.ardakeles.ledger.domain.ContentionException;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.domain.TransferResult;
import com.ardakeles.ledger.repository.LedgerRepository;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Optimistic concurrency control: read without locks, decide, then write only if nothing changed.
 *
 * <ol>
 *   <li><b>Claim the idempotency key</b> (same as the pessimistic variant).</li>
 *   <li><b>Read both accounts with plain SELECTs</b>, remembering each one's {@code version}.</li>
 *   <li><b>Decide</b> (currency, funds) on what was read. It may be stale -- step 4 finds out.</li>
 *   <li><b>Write each balance with {@code WHERE version = <what I read>}</b>. Zero rows updated means
 *       someone changed that account since, so the decision may be wrong: throw, roll back everything
 *       (claim included), and let the retry loop start over from a fresh read. When every update
 *       succeeds, each account provably still held the state the decision was based on.</li>
 *   <li><b>Write the entries.</b></li>
 * </ol>
 *
 * <p><b>Optimistic does not mean deadlock-free.</b> The version-checked UPDATEs take row locks in the
 * order they execute, held until commit. Updating A then B in one transaction and B then A in another
 * is the same deadlock as before, so the UPDATEs run in ascending account id order, the same total-order
 * argument as the pessimistic variant.
 *
 * <p>The retry loop is deliberately OUTSIDE the transaction: a failed attempt is rolled back completely
 * and the next attempt is a fresh transaction. It uses a {@link TransactionTemplate} inside a plain loop
 * rather than {@code @Transactional}, because a proxy-based annotation on a method called from the same
 * class would be silently skipped (self-invocation), and one around the loop would retry inside a
 * dead transaction. Retries are bounded, with exponentially growing full-jitter backoff so that
 * conflicting transactions do not wake together and collide again. The default budget of 20 attempts was
 * chosen from measurement: at 10, about 1 in 1,000 transfers under 20-way contention exhausted it; at 20,
 * about 1 in 10,000 or fewer; 50 bought nothing measurable. When the budget runs out the
 * transfer fails with {@link ContentionException} (nothing posted; the client may retry the same key).
 *
 * <p>One possible refinement, deliberately NOT done: the credited account's version check is not needed
 * for correctness (a credit commutes with other changes and never invalidates a funds decision), so it
 * could be skipped to reduce conflicts. The conventional "the state I read is the state I wrote against"
 * rule is simpler to reason about and to defend.
 */
@Service
public class OptimisticTransferService implements TransferService {

    private static final long BASE_BACKOFF_NANOS = TimeUnit.MICROSECONDS.toNanos(200);
    private static final long MAX_BACKOFF_NANOS = TimeUnit.MILLISECONDS.toNanos(20);

    private final LedgerRepository repository;
    private final TransferPosting posting;
    private final TransactionTemplate tx;
    private final int maxAttempts;
    private final Duration lockTimeout;
    private final Counter conflicts;
    private final Counter exhausted;

    /** Thrown inside the transaction to roll it back; caught by the retry loop. Never escapes this class. */
    private static final class OptimisticConflictException extends RuntimeException {
        OptimisticConflictException(long accountId) {
            super("account " + accountId + " changed since it was read", null, false, false); // no stack trace: control flow
        }
    }

    public OptimisticTransferService(LedgerRepository repository, TransferPosting posting,
                                     PlatformTransactionManager transactionManager, MeterRegistry meters,
                                     @Value("${ledger.optimistic.max-attempts:20}") int maxAttempts,
                                     @Value("${ledger.lock-timeout:PT5S}") Duration lockTimeout) {
        if (maxAttempts < 1) {
            throw new IllegalArgumentException("ledger.optimistic.max-attempts must be >= 1");
        }
        this.repository = repository;
        this.posting = posting;
        this.maxAttempts = maxAttempts;
        this.lockTimeout = TransferPosting.requireValidLockTimeout(lockTimeout);
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
        this.conflicts = Counter.builder("ledger.transfer.conflicts")
                .description("Optimistic version checks that failed and forced a retry").register(meters);
        this.exhausted = Counter.builder("ledger.transfer.contention.exhausted")
                .description("Transfers abandoned after exhausting their retry budget").register(meters);
    }

    @Override
    public TransferResult execute(String idempotencyKey, TransferRequest request) {
        for (int attempt = 1; ; attempt++) {
            try {
                return tx.execute(status -> attemptOnce(idempotencyKey, request));
            } catch (OptimisticConflictException conflict) {
                conflicts.increment();
                if (attempt >= maxAttempts) {
                    exhausted.increment();
                    throw new ContentionException(attempt);
                }
                backoff(attempt);
            }
        }
    }

    private TransferResult attemptOnce(String idempotencyKey, TransferRequest request) {
        // The version-checked UPDATEs (and a duplicate key's INSERT) still wait on row locks; bound that wait.
        repository.setLocalLockTimeout(lockTimeout);
        Optional<Transfer> claimed = posting.claim(idempotencyKey, request);
        if (claimed.isEmpty()) {
            return posting.replay(idempotencyKey, request);
        }
        Transfer transfer = claimed.get();

        Account from = repository.findAccount(request.fromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        Account to = repository.findAccount(request.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));
        posting.assertPostable(from, to, request);

        // Version-checked writes, in ascending id order (see class comment). A failed check aborts the whole
        // attempt, including an update already applied to the other account: the transaction rolls back.
        record Write(Account account, long delta) {
        }
        List<Write> writes = List.of(new Write(from, -request.amountMinor()), new Write(to, request.amountMinor())).stream()
                .sorted(Comparator.comparingLong(w -> w.account().id()))
                .toList();
        for (Write w : writes) {
            if (!repository.applyBalanceDeltaIfVersion(w.account().id(), w.delta(), w.account().version())) {
                throw new OptimisticConflictException(w.account().id());
            }
        }
        posting.writeEntries(transfer, from, to, request);
        return new TransferResult(transfer, false);
    }

    /** Full jitter: sleep a random time in [0, min(cap, base * 2^attempt)]. */
    private static void backoff(int attempt) {
        long ceiling = Math.min(MAX_BACKOFF_NANOS, BASE_BACKOFF_NANOS << Math.min(attempt, 16));
        try {
            TimeUnit.NANOSECONDS.sleep(ThreadLocalRandom.current().nextLong(ceiling + 1));
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException("interrupted while backing off", e);
        }
    }
}
