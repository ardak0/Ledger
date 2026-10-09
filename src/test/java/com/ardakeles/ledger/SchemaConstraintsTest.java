package com.ardakeles.ledger;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatCode;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.util.UUID;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the database itself refuses to hold an invalid ledger, using raw SQL that bypasses all
 * application code. If a bug in the Java layer ever writes bad data, these are the rules that
 * stop it. Deferred constraints fire at COMMIT, so each test commits a real transaction.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class SchemaConstraintsTest {

    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;

    TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(txManager);
    }

    @Test
    void balancedTransferCommits() {
        long a = account("USD", false, 1_000);
        long b = account("USD", false, 0);
        String key = newKey();

        tx.executeWithoutResult(s -> {
            long t = transfer(key);
            entry(t, a, -500, "USD");
            entry(t, b, 500, "USD");
        });

        assertThat(entriesFor(key)).isEqualTo(2);
    }

    @Test
    void transferWithNoEntriesIsRejectedAndRolledBack() {
        String key = newKey();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> transfer(key)))
                .hasMessageContaining("violates double-entry invariant");

        assertThat(transfersWithKey(key)).isZero();
    }

    @Test
    void unbalancedEntriesAreRejectedAndRolledBack() {
        long a = account("USD", false, 1_000);
        long b = account("USD", false, 0);
        String key = newKey();

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    long t = transfer(key);
                    entry(t, a, -500, "USD");
                    entry(t, b, 499, "USD"); // one minor unit short
                }))
                .hasMessageContaining("violates double-entry invariant");

        assertThat(transfersWithKey(key)).isZero();
    }

    @Test
    void transferBetweenAnAccountAndItselfIsRejected() {
        long a = account("USD", false, 1_000);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    long t = transfer(newKey());
                    entry(t, a, -100, "USD");
                    entry(t, a, 100, "USD"); // sums to zero but moves nothing anywhere
                }))
                .hasMessageContaining("accounts=1");
    }

    @Test
    void mixedCurrencyTransferIsRejectedEvenThoughItSumsToZero() {
        long usd = account("USD", false, 1_000);
        long eur = account("EUR", false, 0);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    long t = transfer(newKey());
                    entry(t, usd, -100, "USD");
                    entry(t, eur, 100, "EUR");
                }))
                .hasMessageContaining("currencies=2");
    }

    @Test
    void entryCurrencyMustMatchItsAccountCurrency() {
        long usd = account("USD", false, 1_000);

        // Fails immediately (foreign key), not at commit.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> {
                    long t = transfer(newKey());
                    entry(t, usd, -100, "EUR");
                }))
                .hasMessageContaining("entry_account_currency_fk");
    }

    @Test
    void strayEntryCannotBeAppendedToAnAlreadyCommittedTransfer() {
        long a = account("USD", false, 1_000);
        long b = account("USD", false, 0);
        String key = newKey();
        tx.executeWithoutResult(s -> {
            long t = transfer(key);
            entry(t, a, -500, "USD");
            entry(t, b, 500, "USD");
        });
        long committedTransferId = jdbc.queryForObject(
                "SELECT id FROM transfer WHERE idempotency_key = ?", Long.class, key);

        // The transfer row is not touched, so only the entry-side trigger can catch this.
        assertThatThrownBy(() -> tx.executeWithoutResult(s -> entry(committedTransferId, a, 50, "USD")))
                .hasMessageContaining("violates double-entry invariant");

        assertThat(entriesFor(key)).isEqualTo(2);
    }

    @Test
    void zeroAmountEntryIsRejected() {
        long a = account("USD", false, 0);

        assertThatThrownBy(() -> tx.executeWithoutResult(s -> entry(transfer(newKey()), a, 0, "USD")))
                .hasMessageContaining("entry_amount_non_zero");
    }

    @Test
    void nonSystemAccountCannotGoNegative() {
        long a = account("USD", false, 100);

        assertThatThrownBy(() -> jdbc.update("UPDATE account SET balance_minor = -1 WHERE id = ?", a))
                .hasMessageContaining("account_balance_non_negative");
    }

    @Test
    void systemAccountMayGoNegative() {
        long funding = account("USD", true, 0);

        assertThatCode(() -> jdbc.update("UPDATE account SET balance_minor = -1_000_000 WHERE id = ?", funding))
                .doesNotThrowAnyException();
    }

    @Test
    void postedEntriesAndTransfersAreAppendOnly() {
        long a = account("USD", false, 1_000);
        long b = account("USD", false, 0);
        String key = newKey();
        tx.executeWithoutResult(s -> {
            long t = transfer(key);
            entry(t, a, -500, "USD");
            entry(t, b, 500, "USD");
        });

        assertThatThrownBy(() -> jdbc.update(
                        "UPDATE entry SET amount_minor = 1 WHERE transfer_id = (SELECT id FROM transfer WHERE idempotency_key = ?)", key))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update(
                        "DELETE FROM entry WHERE transfer_id = (SELECT id FROM transfer WHERE idempotency_key = ?)", key))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("UPDATE transfer SET idempotency_key = 'x' WHERE idempotency_key = ?", key))
                .hasMessageContaining("append-only");
        assertThatThrownBy(() -> jdbc.update("DELETE FROM transfer WHERE idempotency_key = ?", key))
                .hasMessageContaining("append-only");
    }

    // ---- raw-SQL helpers: deliberately do not use any application code ----

    long account(String currency, boolean system, long balance) {
        return jdbc.queryForObject(
                "INSERT INTO account (currency, is_system, balance_minor) VALUES (?, ?, ?) RETURNING id",
                Long.class, currency, system, balance);
    }

    long transfer(String key) {
        return jdbc.queryForObject(
                "INSERT INTO transfer (idempotency_key, request_hash) VALUES (?, ?) RETURNING id",
                Long.class, key, new byte[32]);
    }

    void entry(long transferId, long accountId, long amount, String currency) {
        jdbc.update(
                "INSERT INTO entry (transfer_id, account_id, amount_minor, currency) VALUES (?, ?, ?, ?)",
                transferId, accountId, amount, currency);
    }

    int transfersWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM transfer WHERE idempotency_key = ?", Integer.class, key);
    }

    int entriesFor(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM entry e JOIN transfer t ON t.id = e.transfer_id WHERE t.idempotency_key = ?",
                Integer.class, key);
    }

    static String newKey() {
        return UUID.randomUUID().toString();
    }
}
