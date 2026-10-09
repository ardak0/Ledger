package com.ardakeles.ledger.service;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountNotFoundException;
import com.ardakeles.ledger.domain.CurrencyMismatchException;
import com.ardakeles.ledger.domain.IdempotencyKeyReuseException;
import com.ardakeles.ledger.domain.InsufficientFundsException;
import com.ardakeles.ledger.domain.StoredTransfer;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.domain.TransferResult;
import com.ardakeles.ledger.repository.LedgerRepository;
import java.security.MessageDigest;
import java.util.Optional;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Isolation;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final LedgerRepository repository;
    private final LockingMode lockingMode;

    public TransferService(LedgerRepository repository, LockingMode lockingMode) {
        this.repository = repository;
        this.lockingMode = lockingMode;
    }

    /**
     * Posts a transfer exactly once per idempotency key, safely under concurrency.
     *
     * <p>Order of operations matters, and each step is placed deliberately:
     * <ol>
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
     */
    @Transactional(isolation = Isolation.READ_COMMITTED)
    public TransferResult execute(String idempotencyKey, TransferRequest request) {
        Optional<Transfer> claimed = repository.insertTransferIfAbsent(idempotencyKey, request);
        if (claimed.isEmpty()) {
            return replay(idempotencyKey, request);
        }
        Transfer transfer = claimed.get();

        LockedAccounts locked = lockAccounts(request);
        Account from = locked.from();
        Account to = locked.to();

        if (from.currency() != request.currency() || to.currency() != request.currency()) {
            throw new CurrencyMismatchException("request is in " + request.currency() + " but accounts are "
                    + from.currency() + " and " + to.currency());
        }
        if (!from.canDebit(request.amountMinor())) {
            throw new InsufficientFundsException(from.id(), from.balanceMinor(), request.amountMinor());
        }

        // amountMinor > 0 is guaranteed by TransferRequest, so negating it cannot overflow.
        repository.insertEntry(transfer.id(), from.id(), -request.amountMinor(), request.currency());
        repository.insertEntry(transfer.id(), to.id(), request.amountMinor(), request.currency());
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
        boolean ordered = lockingMode.isOrdered(); // read once so both locks follow the same rule
        long firstId = ordered ? Math.min(fromId, toId) : fromId; // unordered: see LockingMode
        long secondId = ordered ? Math.max(fromId, toId) : toId;

        Account first = repository.lockAccount(firstId).orElseThrow(() -> new AccountNotFoundException(firstId));
        Account second = repository.lockAccount(secondId).orElseThrow(() -> new AccountNotFoundException(secondId));
        return firstId == fromId ? new LockedAccounts(first, second) : new LockedAccounts(second, first);
    }

    /**
     * The key already belongs to a committed transfer. Same request hash means the payload is
     * identical, so we return the original (echoing the incoming request is exact, given SHA-256);
     * a different hash is a client bug and is refused.
     */
    private TransferResult replay(String idempotencyKey, TransferRequest request) {
        // This statement takes a fresh snapshot (READ COMMITTED), which sees the row whose commit
        // our blocked INSERT just waited for. Transfers are append-only, so it cannot have vanished.
        StoredTransfer existing = repository.findTransferByKey(idempotencyKey)
                .orElseThrow(() -> new IllegalStateException(
                        "idempotency key conflicted but no committed transfer was found: " + idempotencyKey));
        if (!MessageDigest.isEqual(existing.requestHash(), request.sha256())) {
            throw new IdempotencyKeyReuseException(idempotencyKey);
        }
        return new TransferResult(new Transfer(existing.id(), idempotencyKey, request, existing.createdAt()), true);
    }
}
