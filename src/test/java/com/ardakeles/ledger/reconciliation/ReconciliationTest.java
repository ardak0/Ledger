package com.ardakeles.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;

import com.ardakeles.ledger.IsolatedDatabaseConfig;
import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountDrift;
import com.ardakeles.ledger.domain.BrokenTransfer;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.ReconciliationReport;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.PessimisticTransferService;
import io.micrometer.core.instrument.MeterRegistry;
import java.sql.Connection;
import java.sql.SQLException;
import java.sql.Statement;
import java.util.List;
import java.util.Optional;
import java.util.Set;
import java.util.UUID;
import java.util.stream.Collectors;
import javax.sql.DataSource;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Reconciliation must (a) find real corruption exactly, and (b) find nothing on a healthy ledger. The
 * corruption tests create it deliberately with raw SQL, assert on the specific rows they damaged, and
 * repair it in a finally block: the database is shared by all tests and reconciliation looks at all of it.
 */
@SpringBootTest
@Import(IsolatedDatabaseConfig.class)
class ReconciliationTest {

    @Autowired ReconciliationService reconciliation;
    @Autowired PessimisticTransferService transfers;
    @Autowired LedgerRepository repository;
    @Autowired JdbcTemplate jdbc;
    @Autowired PlatformTransactionManager txManager;
    @Autowired DataSource dataSource;
    @Autowired MeterRegistry meters;

    @Test
    void aLedgerBuiltOnlyThroughTheServiceReconcilesClean() {
        Account funding = repository.createSystemAccount(Currency.USD);
        Account alice = repository.createAccount(Currency.USD);
        Account bob = repository.createAccount(Currency.USD);
        transfers.execute(key(), new TransferRequest(funding.id(), alice.id(), 1_000, Currency.USD));
        transfers.execute(key(), new TransferRequest(alice.id(), bob.id(), 250, Currency.USD));

        ReconciliationReport report = reconcile();

        assertThat(report.isClean()).as("drift: %s, broken: %s", report.driftedAccounts(), report.brokenTransfers()).isTrue();
        assertThat(report.checked().accounts()).isGreaterThanOrEqualTo(3);
        assertThat(report.checked().entries()).isGreaterThanOrEqualTo(4);
        assertThat(discrepancyGauge()).isZero();
    }

    @Test
    void corruptedCachedBalancesAreFoundWithTheExactDifferenceInBothDirections() {
        Account funding = repository.createSystemAccount(Currency.USD);
        Account inflated = repository.createAccount(Currency.USD);
        Account deflated = repository.createAccount(Currency.USD);
        Account neverFunded = repository.createAccount(Currency.USD); // no entries at all
        transfers.execute(key(), new TransferRequest(funding.id(), inflated.id(), 100, Currency.USD));
        transfers.execute(key(), new TransferRequest(funding.id(), deflated.id(), 100, Currency.USD));
        try {
            adjustCachedBalance(inflated, +7);
            adjustCachedBalance(deflated, -3);
            adjustCachedBalance(neverFunded, +5);

            ReconciliationReport report = reconcile();

            // Exactly these three, and nothing else in the whole database.
            assertThat(report.driftedAccounts()).containsExactlyInAnyOrder(
                    new AccountDrift(inflated.id(), 107, 100),
                    new AccountDrift(deflated.id(), 97, 100),
                    new AccountDrift(neverFunded.id(), 5, 0));
            assertThat(report.driftedAccounts()).extracting(AccountDrift::differenceMinor)
                    .containsExactlyInAnyOrder(7L, -3L, 5L);
            assertThat(report.brokenTransfers()).isEmpty();
            assertThat(discrepancyGauge()).isEqualTo(3);
        } finally {
            adjustCachedBalance(inflated, -7);
            adjustCachedBalance(deflated, +3);
            adjustCachedBalance(neverFunded, -5);
        }

        assertThat(reconcile().isClean()).as("after repairing the damage").isTrue();
        assertThat(discrepancyGauge()).isZero();
    }

    /** The deferred trigger makes this impossible, so the trigger is switched off for one transaction to create it. */
    @Test
    void aTransferThatDoesNotSumToZeroIsFoundEvenIfTheTriggerWasBypassed() {
        Account a = repository.createAccount(Currency.USD);
        Account b = repository.createAccount(Currency.USD);
        long transferId = withTriggersDisabled(() -> {
            long id = insertTransferRow();
            insertEntryRow(id, a, +100);
            insertEntryRow(id, b, -90);
            return id;
        });
        try {
            ReconciliationReport report = reconcile();

            assertThat(report.brokenTransfers()).containsExactly(new BrokenTransfer(transferId, 10, 2, 1));
            // Their cached balances never moved, so both accounts also disagree with their entries.
            assertThat(report.driftedAccounts()).extracting(AccountDrift::accountId)
                    .containsExactlyInAnyOrder(a.id(), b.id());
        } finally {
            withTriggersDisabled(() -> deleteTransfer(transferId));
        }
        assertThat(reconcile().isClean()).isTrue();
    }

    @Test
    void aTransferWithNoEntriesAtAllIsFound() {
        long transferId = withTriggersDisabled(this::insertTransferRow);
        try {
            assertThat(reconcile().brokenTransfers()).containsExactly(new BrokenTransfer(transferId, 0, 0, 0));
        } finally {
            withTriggersDisabled(() -> deleteTransfer(transferId));
        }
        assertThat(reconcile().isClean()).isTrue();
    }

    @Test
    void aSecondRunIsSkippedWhileAnotherHoldsTheLockAndWorksAgainAfterwards() throws SQLException {
        try (Connection other = dataSource.getConnection(); Statement st = other.createStatement()) {
            st.execute("SELECT pg_advisory_lock(hashtext('" + ReconciliationService.LOCK_NAME + "'))");
            try {
                assertThat(reconciliation.reconcile()).as("skipped while locked elsewhere").isEmpty();
            } finally {
                st.execute("SELECT pg_advisory_unlock(hashtext('" + ReconciliationService.LOCK_NAME + "'))");
            }
        }

        assertThat(reconciliation.reconcile()).as("runs once the lock is free").isPresent();
    }

    // ---- helpers ----

    private ReconciliationReport reconcile() {
        Optional<ReconciliationReport> report = reconciliation.reconcile();
        assertThat(report).as("run was not skipped").isPresent();
        return report.get();
    }

    private void adjustCachedBalance(Account account, long delta) {
        jdbc.update("UPDATE account SET balance_minor = balance_minor + ? WHERE id = ?", delta, account.id());
    }

    private double discrepancyGauge() {
        return meters.get("ledger.reconciliation.discrepancies").gauge().value();
    }

    private <T> T withTriggersDisabled(java.util.function.Supplier<T> work) {
        return new TransactionTemplate(txManager).execute(status -> {
            jdbc.execute("SET LOCAL session_replication_role = replica"); // disables user triggers for this transaction
            return work.get();
        });
    }

    private long insertTransferRow() {
        return jdbc.queryForObject("INSERT INTO transfer (idempotency_key, request_hash) VALUES (?, ?) RETURNING id",
                Long.class, key(), new byte[32]);
    }

    private void insertEntryRow(long transferId, Account account, long amount) {
        jdbc.update("INSERT INTO entry (transfer_id, account_id, amount_minor, currency) VALUES (?, ?, ?, 'USD')",
                transferId, account.id(), amount);
    }

    private Void deleteTransfer(long transferId) {
        jdbc.update("DELETE FROM entry WHERE transfer_id = ?", transferId);
        jdbc.update("DELETE FROM transfer WHERE id = ?", transferId);
        return null;
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
