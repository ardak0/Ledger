package com.ardakeles.ledger.repository;

import static com.ardakeles.ledger.jooq.Tables.ACCOUNT;
import static com.ardakeles.ledger.jooq.Tables.ENTRY;
import static com.ardakeles.ledger.jooq.Tables.TRANSFER;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.LedgerEntry;
import com.ardakeles.ledger.domain.StoredTransfer;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.jooq.tables.records.AccountRecord;
import java.util.List;
import java.util.Optional;
import org.jooq.DSLContext;
import org.springframework.stereotype.Repository;

/**
 * All SQL lives here. Methods join the caller's Spring transaction (jOOQ's Spring starter wraps
 * the DataSource in TransactionAwareDataSourceProxy), so this class never commits or rolls back.
 */
@Repository
public class LedgerRepository {

    private final DSLContext dsl;

    public LedgerRepository(DSLContext dsl) {
        this.dsl = dsl;
    }

    public Account createAccount(Currency currency) {
        return insertAccount(currency, false);
    }

    /** System accounts may go negative. Deliberately a separate method: never reachable from client input. */
    public Account createSystemAccount(Currency currency) {
        return insertAccount(currency, true);
    }

    private Account insertAccount(Currency currency, boolean system) {
        AccountRecord created = dsl.insertInto(ACCOUNT)
                .set(ACCOUNT.CURRENCY, currency.name())
                .set(ACCOUNT.IS_SYSTEM, system)
                .returning()
                .fetchSingle();
        return toAccount(created);
    }

    public Optional<Account> findAccount(long id) {
        return dsl.selectFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchOptional().map(LedgerRepository::toAccount);
    }

    /**
     * Claims an idempotency key by inserting the transfer row, atomically, in one statement.
     *
     * <p>Returns the new transfer if this call won the key, or empty if the key already exists.
     * Under READ COMMITTED, if another transaction is inserting the same key but has not yet
     * committed, this statement <em>blocks</em> until that transaction finishes: if it commits we
     * get "conflict" (empty), if it rolls back we proceed and win. No exception is raised either
     * way, so the surrounding transaction stays usable -- unlike a plain INSERT, where a unique
     * violation aborts the transaction.
     */
    public Optional<Transfer> insertTransferIfAbsent(String idempotencyKey, TransferRequest request) {
        return dsl.insertInto(TRANSFER)
                .set(TRANSFER.IDEMPOTENCY_KEY, idempotencyKey)
                .set(TRANSFER.REQUEST_HASH, request.sha256())
                .onConflict(TRANSFER.IDEMPOTENCY_KEY).doNothing()
                .returningResult(TRANSFER.ID, TRANSFER.CREATED_AT)
                .fetchOptional()
                .map(row -> new Transfer(row.value1(), idempotencyKey, request, row.value2()));
    }

    public Optional<StoredTransfer> findTransferByKey(String idempotencyKey) {
        return dsl.select(TRANSFER.ID, TRANSFER.REQUEST_HASH, TRANSFER.CREATED_AT)
                .from(TRANSFER)
                .where(TRANSFER.IDEMPOTENCY_KEY.eq(idempotencyKey))
                .fetchOptional(r -> new StoredTransfer(r.value1(), r.value2(), r.value3()));
    }

    /** {@code amountMinor} is signed: negative debits the account, positive credits it. */
    public void insertEntry(long transferId, long accountId, long amountMinor, Currency currency) {
        dsl.insertInto(ENTRY)
                .set(ENTRY.TRANSFER_ID, transferId)
                .set(ENTRY.ACCOUNT_ID, accountId)
                .set(ENTRY.AMOUNT_MINOR, amountMinor)
                .set(ENTRY.CURRENCY, currency.name())
                .execute();
    }

    /**
     * Atomic delta update: the database computes {@code balance + delta} against the latest committed
     * row, so no application-side read-modify-write is involved. Every mutation bumps version.
     */
    public void applyBalanceDelta(long accountId, long deltaMinor) {
        int updated = dsl.update(ACCOUNT)
                .set(ACCOUNT.BALANCE_MINOR, ACCOUNT.BALANCE_MINOR.plus(deltaMinor))
                .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
                .where(ACCOUNT.ID.eq(accountId))
                .execute();
        if (updated != 1) {
            throw new IllegalStateException("expected to update exactly 1 account row for id " + accountId + ", got " + updated);
        }
    }

    /**
     * Keyset pagination: entries of one account with id greater than {@code afterId}, oldest first,
     * served by the (account_id, id) index. Unlike OFFSET, new entries arriving mid-scan cannot
     * shift pages, and cost does not grow with page depth.
     *
     * <p>Known limitation: ids come from a sequence but commit in arbitrary order, so a slow
     * transaction can commit an entry with a lower id after a reader has already paged past it.
     * Acceptable for a statement view; a reconciling consumer would need a stronger scheme.
     */
    public List<LedgerEntry> findEntries(long accountId, long afterId, int limit) {
        return dsl.selectFrom(ENTRY)
                .where(ENTRY.ACCOUNT_ID.eq(accountId).and(ENTRY.ID.gt(afterId)))
                .orderBy(ENTRY.ID.asc())
                .limit(limit)
                .fetch(r -> new LedgerEntry(r.getId(), r.getTransferId(), r.getAccountId(), r.getAmountMinor(),
                        Currency.valueOf(r.getCurrency()), r.getCreatedAt()));
    }

    private static Account toAccount(AccountRecord r) {
        return new Account(r.getId(), Currency.valueOf(r.getCurrency()), r.getIsSystem(), r.getBalanceMinor(), r.getVersion(),
                r.getCreatedAt());
    }
}
