package com.ardakeles.ledger.repository;

import static com.ardakeles.ledger.jooq.Tables.ACCOUNT;
import static com.ardakeles.ledger.jooq.Tables.ENTRY;
import static com.ardakeles.ledger.jooq.Tables.TRANSFER;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.BalanceSnapshot;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.LedgerEntry;
import com.ardakeles.ledger.domain.StoredTransfer;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.jooq.tables.records.AccountRecord;
import java.math.BigDecimal;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import org.jooq.DSLContext;
import org.jooq.Field;
import org.jooq.impl.DSL;
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

    /**
     * Plain snapshot read, no lock. Fine for display (balance, statements); NEVER use the result
     * to make a decision about moving money -- it can be stale the instant it is returned. Use
     * {@link #lockAccount} for that.
     */
    public Optional<Account> findAccount(long id) {
        return dsl.selectFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).fetchOptional().map(LedgerRepository::toAccount);
    }

    /**
     * Reads an account and takes its row lock (SELECT ... FOR UPDATE), held until the transaction
     * ends. If another transaction holds the lock, this blocks; when it is released, Postgres
     * re-reads the latest committed version of the row, so the balance returned here is current
     * and cannot change under us until we commit.
     */
    public Optional<Account> lockAccount(long id) {
        return dsl.selectFrom(ACCOUNT).where(ACCOUNT.ID.eq(id)).forUpdate().fetchOptional()
                .map(LedgerRepository::toAccount);
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

    /**
     * Balances of {@code accountIds} and the sum over every account, in ONE statement. A statement sees
     * a single snapshot of committed data (READ COMMITTED takes its snapshot per statement), so both
     * figures are mutually consistent: no transfer can be half-visible. Plain read, takes no locks.
     */
    public BalanceSnapshot snapshotBalances(List<Long> accountIds) {
        var all = ACCOUNT.as("all_accounts");
        Field<BigDecimal> globalSum = DSL.field(DSL.select(DSL.sum(all.BALANCE_MINOR)).from(all)).as("global_sum");
        var rows = dsl.select(ACCOUNT.ID, ACCOUNT.BALANCE_MINOR, globalSum)
                .from(ACCOUNT)
                .where(ACCOUNT.ID.in(accountIds))
                .orderBy(ACCOUNT.ID)
                .fetch();
        Map<Long, Long> balances = new LinkedHashMap<>();
        rows.forEach(r -> balances.put(r.get(ACCOUNT.ID), r.get(ACCOUNT.BALANCE_MINOR)));
        long global = rows.isEmpty() ? 0 : rows.get(0).get(globalSum).longValueExact();
        return new BalanceSnapshot(balances, global);
    }

    private static Account toAccount(AccountRecord r) {
        return new Account(r.getId(), Currency.valueOf(r.getCurrency()), r.getIsSystem(), r.getBalanceMinor(), r.getVersion(),
                r.getCreatedAt());
    }
}
