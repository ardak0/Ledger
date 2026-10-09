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
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final LedgerRepository repository;

    public TransferService(LedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * Posts a transfer exactly once per idempotency key.
     *
     * <p>The key is claimed FIRST, by inserting the transfer row. Whoever inserts it owns the
     * execution; anyone else with the same key either gets the original back (same payload) or an
     * error (different payload). Claiming before any other work means a duplicate never takes
     * account locks (added in step 5), so it cannot deadlock with, or queue behind, real work. If
     * the owner fails for any reason the whole transaction rolls back, including the claim, so a
     * failed attempt does not burn the key.
     *
     * <p>STEP 4 STATE: idempotency is concurrency-safe, but account reads are still lock-free, so
     * transfers with <em>different</em> keys touching the same accounts are not yet safe. Step 5
     * replaces the reads with ordered row locks.
     */
    @Transactional
    public TransferResult execute(String idempotencyKey, TransferRequest request) {
        Optional<Transfer> claimed = repository.insertTransferIfAbsent(idempotencyKey, request);
        if (claimed.isEmpty()) {
            return replay(idempotencyKey, request);
        }
        Transfer transfer = claimed.get();

        Account from = repository.findAccount(request.fromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        Account to = repository.findAccount(request.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

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
