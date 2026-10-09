package com.ardakeles.ledger.repository;

import static com.ardakeles.ledger.jooq.Tables.ACCOUNT;
import static com.ardakeles.ledger.jooq.Tables.ENTRY;
import static com.ardakeles.ledger.jooq.Tables.TRANSFER;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.jooq.tables.records.AccountRecord;
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

    public Transfer insertTransfer(String idempotencyKey, TransferRequest request) {
        var row = dsl.insertInto(TRANSFER)
                .set(TRANSFER.IDEMPOTENCY_KEY, idempotencyKey)
                .set(TRANSFER.REQUEST_HASH, request.sha256())
                .returningResult(TRANSFER.ID, TRANSFER.CREATED_AT)
                .fetchSingle();
        return new Transfer(row.value1(), idempotencyKey, request, row.value2());
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

    private static Account toAccount(AccountRecord r) {
        return new Account(r.getId(), Currency.valueOf(r.getCurrency()), r.getIsSystem(), r.getBalanceMinor(), r.getVersion());
    }
}
