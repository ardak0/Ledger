package com.ardakeles.ledger.service;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountNotFoundException;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.domain.TransferResult;
import com.ardakeles.ledger.repository.LedgerRepository;
import java.time.Duration;
import java.util.Optional;
import java.util.function.BooleanSupplier;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

@Service
public class PessimisticTransferService implements TransferService {

    private final LedgerRepository repository;
    private final TransferPosting posting;
    private final TransactionTemplate tx;
    private final Duration lockTimeout;
    /** Always true for the application's bean; only {@link #withLockingMode} instances can say otherwise. */
    private final BooleanSupplier ordered;

    @Autowired
    public PessimisticTransferService(LedgerRepository repository, TransferPosting posting,
                                      PlatformTransactionManager transactionManager,
                                      @Value("${ledger.lock-timeout:PT5S}") Duration lockTimeout) {
        this(repository, posting, transactionManager, lockTimeout, () -> true);
    }

    private PessimisticTransferService(LedgerRepository repository, TransferPosting posting,
                                       PlatformTransactionManager transactionManager, Duration lockTimeout,
                                       BooleanSupplier ordered) {
        this.repository = repository;
        this.posting = posting;
        this.lockTimeout = TransferPosting.requireValidLockTimeout(lockTimeout);
        this.ordered = ordered;
        this.tx = new TransactionTemplate(transactionManager);
        this.tx.setIsolationLevel(TransactionDefinition.ISOLATION_READ_COMMITTED);
    }

    /**
     * DELIBERATE DEMONSTRATION OF A BUG -- see {@link LockingMode}. Returns a separate instance, NOT a Spring
     * bean, whose lock order follows {@code lockingMode}. Used only by the /demo page and the negative-control
     * test; the application's injected {@link TransferService} is never this instance and has no switch.
     */
    public static PessimisticTransferService withLockingMode(LedgerRepository repository,
                                                             PlatformTransactionManager transactionManager,
                                                             Duration lockTimeout, LockingMode lockingMode) {
        return new PessimisticTransferService(repository, new TransferPosting(repository), transactionManager,
                lockTimeout, lockingMode::isOrdered);
    }

    /**
     * Posts a transfer exactly once per idempotency key, safely under concurrency.
     *
     * <p>Order of operations matters, and each step is placed deliberately:
     * <ol>
     *   <li><b>Bound lock waits</b> for this transaction ({@code SET LOCAL lock_timeout}), so a stuck lock
     *       holder fails this transfer with a retryable error instead of pinning a pooled connection forever.</li>
     *   <li><b>Claim the idempotency key</b> by inserting the transfer row. A duplicate waits here,
     *       holding no account locks, so it cannot deadlock with real work. If this transaction
     *       later fails, the claim rolls back with it, so a failed attempt does not burn the key.</li>
     *   <li><b>Lock both accounts</b> in ascending id order (see {@link #lockAccounts}). This is the
     *       deadlock-avoidance step, and it must come before any statement that would lock an
     *       account row implicitly (the UPDATEs below), since statement order would otherwise
     *       become lock order.</li>
     *   <li><b>Decide</b> (currency, funds) using the locked rows. These reads are current and
     *       cannot change before commit, so check-then-write is atomic against other writers.</li>
     *   <li><b>Write</b> entries and balance deltas. Both rows are already ours; order is irrelevant.</li>
     * </ol>
     *
     * <p>READ COMMITTED is sufficient (SERIALIZABLE is not needed) because the invariant is
     * per-row and every writer takes the row lock first; it is spelled out here because the
     * argument depends on it. The deferred trigger then verifies the double-entry sum at commit.
     *
     * <p>The transaction is opened with a {@link TransactionTemplate} rather than {@code @Transactional}
     * so that it also applies to {@link #withLockingMode} instances, which are not Spring proxies.
     */
    @Override
    public TransferResult execute(String idempotencyKey, TransferRequest request) {
        return tx.execute(status -> post(idempotencyKey, request));
    }

    private TransferResult post(String idempotencyKey, TransferRequest request) {
        repository.setLocalLockTimeout(lockTimeout);
        Optional<Transfer> claimed = posting.claim(idempotencyKey, request);
        if (claimed.isEmpty()) {
            return posting.replay(idempotencyKey, request);
        }
        Transfer transfer = claimed.get();

        LockedAccounts locked = lockAccounts(request);
        Account from = locked.from();
        Account to = locked.to();

        posting.assertPostable(from, to, request);
        posting.writeEntries(transfer, from, to, request);
        repository.applyBalanceDelta(from.id(), -request.amountMinor());
        repository.applyBalanceDelta(to.id(), request.amountMinor());
        return new TransferResult(transfer, false);
    }

    private record LockedAccounts(Account from, Account to) {
    }

    /**
     * Takes both row locks, one statement each, lowest id first. Any total order works; ascending
     * id is the natural one. With every transaction acquiring in the same order, a wait-for cycle
     * is impossible: a cycle needs someone holding a higher id while waiting for a lower one.
     * Separate statements (rather than one ORDER BY ... FOR UPDATE) keep the order explicit and
     * independent of how the planner executes a multi-row locking query.
     */
    private LockedAccounts lockAccounts(TransferRequest request) {
        long fromId = request.fromAccountId();
        long toId = request.toAccountId();
        boolean inOrder = ordered.getAsBoolean(); // read once so both locks follow the same rule
        long firstId = inOrder ? Math.min(fromId, toId) : fromId; // unordered: see LockingMode
        long secondId = inOrder ? Math.max(fromId, toId) : toId;

        Account first = repository.lockAccount(firstId).orElseThrow(() -> new AccountNotFoundException(firstId));
        Account second = repository.lockAccount(secondId).orElseThrow(() -> new AccountNotFoundException(secondId));
        return firstId == fromId ? new LockedAccounts(first, second) : new LockedAccounts(second, first);
    }
}
