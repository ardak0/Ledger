package com.ardakeles.ledger.service;

import com.ardakeles.ledger.domain.Account;
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
import org.springframework.stereotype.Component;

/**
 * The steps of posting a transfer that are identical whichever concurrency strategy is used, so
 * the two {@link TransferService} implementations differ only where they are meant to differ:
 * how accounts are read and how balances are written. Every method must be called inside a
 * transaction owned by the caller.
 */
@Component
class TransferPosting {

    private final LedgerRepository repository;

    TransferPosting(LedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * Claims the idempotency key by inserting the transfer row; empty means the key already exists.
     * See {@link LedgerRepository#insertTransferIfAbsent} for the blocking semantics.
     */
    Optional<Transfer> claim(String idempotencyKey, TransferRequest request) {
        return repository.insertTransferIfAbsent(idempotencyKey, request);
    }

    /**
     * The key already belongs to a committed transfer. Same request hash means the payload is
     * identical, so we return the original (echoing the incoming request is exact, given SHA-256);
     * a different hash is a client bug and is refused.
     */
    TransferResult replay(String idempotencyKey, TransferRequest request) {
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

    /** Business rules, checked against account state the caller guarantees is current (locked or version-checked). */
    void assertPostable(Account from, Account to, TransferRequest request) {
        if (from.currency() != request.currency() || to.currency() != request.currency()) {
            throw new CurrencyMismatchException("request is in " + request.currency() + " but accounts are "
                    + from.currency() + " and " + to.currency());
        }
        if (!from.canDebit(request.amountMinor())) {
            throw new InsufficientFundsException(from.id(), from.balanceMinor(), request.amountMinor());
        }
    }

    /** The double entry itself: a debit and a credit that sum to zero (the deferred trigger re-checks at commit). */
    void writeEntries(Transfer transfer, Account from, Account to, TransferRequest request) {
        // amountMinor > 0 is guaranteed by TransferRequest, so negating it cannot overflow.
        repository.insertEntry(transfer.id(), from.id(), -request.amountMinor(), request.currency());
        repository.insertEntry(transfer.id(), to.id(), request.amountMinor(), request.currency());
    }
}
