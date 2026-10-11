package com.ardakeles.ledger.repository;

import static com.ardakeles.ledger.jooq.Tables.ACCOUNT;
import static com.ardakeles.ledger.jooq.Tables.ENTRY;
import static com.ardakeles.ledger.jooq.Tables.TRANSFER;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountDrift;
import com.ardakeles.ledger.domain.BrokenTransfer;
import com.ardakeles.ledger.domain.Capped;
import com.ardakeles.ledger.domain.LedgerCounts;
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
     * Optimistic variant of {@link #applyBalanceDelta}: applies the delta only if the row still has the
     * version the caller read. Returns false (zero rows updated) if anyone changed the account since --
     * the caller must then abandon its decision and retry. Under READ COMMITTED, if a concurrent
     * transaction has the row locked, this statement waits for it to finish and re-checks the WHERE
     * clause against the new row version, so a committed concurrent change always shows up as false.
     */
    public boolean applyBalanceDeltaIfVersion(long accountId, long deltaMinor, long expectedVersion) {
        return dsl.update(ACCOUNT)
                .set(ACCOUNT.BALANCE_MINOR, ACCOUNT.BALANCE_MINOR.plus(deltaMinor))
                .set(ACCOUNT.VERSION, ACCOUNT.VERSION.plus(1))
                .where(ACCOUNT.ID.eq(accountId).and(ACCOUNT.VERSION.eq(expectedVersion)))
                .execute() == 1;
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

    // ---- reconciliation ----
    // These queries are hand-written SQL on purpose: they are analytic (outer join, aggregate, window) and
    // the SQL itself is what a reviewer should read to judge them, so it is not hidden behind the DSL.

    /**
     * Accounts whose cached balance differs from the sum of their entries, in ONE statement and therefore
     * one snapshot: a transfer (entries + both balance updates commit together) is either fully visible
     * or not at all, so a concurrent transfer cannot look like drift. {@code sum()} over BIGINT is NUMERIC
     * in Postgres, so the comparison cannot overflow. Accounts with no entries compare against 0.
     */
    public Capped<AccountDrift> findDriftedAccounts(int limit) {
        var rows = dsl.resultQuery("""
                SELECT a.id, a.balance_minor, COALESCE(s.total, 0) AS computed, count(*) OVER () AS total_rows
                  FROM account a
                  LEFT JOIN (SELECT account_id, sum(amount_minor) AS total FROM entry GROUP BY account_id) s
                    ON s.account_id = a.id
                 WHERE a.balance_minor <> COALESCE(s.total, 0)
                 ORDER BY a.id
                 LIMIT ?
                """, limit).fetch();
        List<AccountDrift> items = rows.map(r -> new AccountDrift(r.get("id", Long.class),
                r.get("balance_minor", Long.class), r.get("computed", BigDecimal.class).longValueExact()));
        return new Capped<>(rows.isEmpty() ? 0 : rows.get(0).get("total_rows", Long.class), items);
    }

    /**
     * Transfers breaking the double-entry rule: fewer than 2 distinct accounts, not exactly one currency,
     * or entries not summing to zero. A LEFT JOIN, so a transfer with no entries at all is found too.
     * This is the deferred trigger's invariant re-checked from outside, in case the trigger was bypassed.
     */
    public Capped<BrokenTransfer> findBrokenTransfers(int limit) {
        var rows = dsl.resultQuery("""
                SELECT t.id, COALESCE(sum(e.amount_minor), 0) AS total,
                       count(DISTINCT e.account_id) AS accounts, count(DISTINCT e.currency) AS currencies,
                       count(*) OVER () AS total_rows
                  FROM transfer t
                  LEFT JOIN entry e ON e.transfer_id = t.id
                 GROUP BY t.id
                HAVING COALESCE(sum(e.amount_minor), 0) <> 0
                    OR count(DISTINCT e.account_id) < 2
                    OR count(DISTINCT e.currency) <> 1
                 ORDER BY t.id
                 LIMIT ?
                """, limit).fetch();
        List<BrokenTransfer> items = rows.map(r -> new BrokenTransfer(r.get("id", Long.class),
                r.get("total", BigDecimal.class).longValueExact(), r.get("accounts", Long.class), r.get("currencies", Long.class)));
        return new Capped<>(rows.isEmpty() ? 0 : rows.get(0).get("total_rows", Long.class), items);
    }

    public LedgerCounts countRows() {
        var r = dsl.fetchSingle("SELECT (SELECT count(*) FROM account) AS accounts, (SELECT count(*) FROM transfer) AS transfers,"
                + " (SELECT count(*) FROM entry) AS entries");
        return new LedgerCounts(r.get("accounts", Long.class), r.get("transfers", Long.class), r.get("entries", Long.class));
    }

    /**
     * Tries to take a Postgres advisory lock held until the end of the current transaction (so it can never
     * be left behind by a crash). False means another transaction, possibly on another instance, holds it.
     */
    public boolean tryAcquireTransactionLock(String name) {
        return Boolean.TRUE.equals(dsl.fetchSingle("SELECT pg_try_advisory_xact_lock(hashtext(?))", name).get(0, Boolean.class));
    }

    private static Account toAccount(AccountRecord r) {
        return new Account(r.getId(), Currency.valueOf(r.getCurrency()), r.getIsSystem(), r.getBalanceMinor(), r.getVersion(),
                r.getCreatedAt());
    }
}
