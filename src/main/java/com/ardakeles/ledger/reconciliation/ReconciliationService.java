package com.ardakeles.ledger.reconciliation;

import com.ardakeles.ledger.domain.LedgerCounts;
import com.ardakeles.ledger.domain.ReconciliationReport;
import com.ardakeles.ledger.repository.LedgerRepository;
import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import java.time.Instant;
import java.util.Optional;
import java.util.concurrent.atomic.AtomicLong;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.TransactionDefinition;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Proves the ledger agrees with itself: recomputes every account's balance from its entries and compares
 * it with the cached {@code balance_minor}, and re-verifies every transfer's double-entry rule. The point
 * is that it finds nothing; this is the check that says so.
 *
 * <p>Everything runs in ONE read-only REPEATABLE READ transaction, i.e. against a single frozen snapshot of
 * committed data. Balances and entries are updated atomically by every transfer, so a snapshot always
 * shows both or neither; separate queries at READ COMMITTED would not, and a transfer committing between
 * them would be reported as drift that does not exist. Readers do not block writers (MVCC), so this is
 * safe to run against live traffic.
 *
 * <p>A transaction-scoped advisory lock makes concurrent invocations (several instances, or a manual run
 * during a scheduled one) skip instead of piling up scans.
 *
 * <p>Cost is a full scan of {@code entry}. Fine for this scale; at real scale one would reconcile
 * incrementally from a watermark and keep the full scan as a periodic audit.
 */
@Service
public class ReconciliationService {

    /** Names the advisory lock; hashed by Postgres into the bigint key space. */
    public static final String LOCK_NAME = "ledger.reconciliation";
    /** Report at most this many offenders per category; the true totals are always reported. */
    static final int MAX_REPORTED = 100;

    private static final Logger log = LoggerFactory.getLogger(ReconciliationService.class);

    private final LedgerRepository repository;
    private final TransactionTemplate snapshotTx;
    private final MeterRegistry meters;
    private final AtomicLong discrepancies = new AtomicLong();

    public ReconciliationService(LedgerRepository repository, PlatformTransactionManager transactionManager,
                                 MeterRegistry meters) {
        this.repository = repository;
        this.meters = meters;
        this.snapshotTx = new TransactionTemplate(transactionManager);
        this.snapshotTx.setIsolationLevel(TransactionDefinition.ISOLATION_REPEATABLE_READ);
        this.snapshotTx.setReadOnly(true);
        Gauge.builder("ledger.reconciliation.discrepancies", discrepancies, AtomicLong::get)
                .description("Drifted accounts plus broken transfers found by the most recent completed run (0 = healthy)")
                .register(meters);
    }

    /** Runs one reconciliation. Empty means another run holds the lock and this call was skipped. */
    public Optional<ReconciliationReport> reconcile() {
        Optional<ReconciliationReport> report;
        try {
            report = snapshotTx.execute(status -> run());
        } catch (RuntimeException e) {
            count("error");
            throw e;
        }
        if (report.isEmpty()) {
            count("skipped");
            log.debug("reconciliation skipped: another run holds the lock");
            return report;
        }
        ReconciliationReport r = report.get();
        discrepancies.set(r.driftedAccountCount() + r.brokenTransferCount());
        count(r.isClean() ? "clean" : "discrepancy");
        logResult(r);
        return report;
    }

    private Optional<ReconciliationReport> run() {
        if (!repository.tryAcquireTransactionLock(LOCK_NAME)) {
            return Optional.empty();
        }
        Instant started = Instant.now();
        long t0 = System.nanoTime();
        LedgerCounts checked = repository.countRows();
        var drifted = repository.findDriftedAccounts(MAX_REPORTED);
        var broken = repository.findBrokenTransfers(MAX_REPORTED);
        return Optional.of(new ReconciliationReport(started, Duration.ofNanos(System.nanoTime() - t0).toMillis(), checked,
                drifted.total(), drifted.items(), broken.total(), broken.items()));
    }

    private void logResult(ReconciliationReport r) {
        r.driftedAccounts().forEach(d -> log.atError().setMessage("reconciliation.account_drift")
                .addKeyValue("accountId", d.accountId())
                .addKeyValue("cachedMinor", d.cachedMinor())
                .addKeyValue("computedMinor", d.computedMinor())
                .addKeyValue("differenceMinor", d.differenceMinor())
                .log());
        r.brokenTransfers().forEach(b -> log.atError().setMessage("reconciliation.broken_transfer")
                .addKeyValue("transferId", b.transferId())
                .addKeyValue("sumMinor", b.sumMinor())
                .addKeyValue("distinctAccounts", b.distinctAccounts())
                .addKeyValue("distinctCurrencies", b.distinctCurrencies())
                .log());
        var event = r.isClean() ? log.atInfo() : log.atError();
        event.setMessage("reconciliation.completed")
                .addKeyValue("clean", r.isClean())
                .addKeyValue("accountsChecked", r.checked().accounts())
                .addKeyValue("transfersChecked", r.checked().transfers())
                .addKeyValue("entriesChecked", r.checked().entries())
                .addKeyValue("driftedAccounts", r.driftedAccountCount())
                .addKeyValue("brokenTransfers", r.brokenTransferCount())
                .addKeyValue("durationMillis", r.durationMillis())
                .log();
    }

    private void count(String result) {
        meters.counter("ledger.reconciliation.runs", "result", result).increment();
    }
}
