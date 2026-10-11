package com.ardakeles.ledger.concurrency;

import static com.ardakeles.ledger.concurrency.LoadHarness.assertMoneyConserved;
import static com.ardakeles.ledger.concurrency.LoadHarness.createPool;
import static com.ardakeles.ledger.concurrency.LoadHarness.randomWorkload;
import static com.ardakeles.ledger.concurrency.LoadHarness.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.ardakeles.ledger.IsolatedDatabaseConfig;
import com.ardakeles.ledger.concurrency.LoadHarness.Pool;
import com.ardakeles.ledger.concurrency.LoadHarness.RunResult;
import com.ardakeles.ledger.domain.ReconciliationReport;
import com.ardakeles.ledger.reconciliation.ReconciliationService;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.PessimisticTransferService;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The reason reconciliation reads from one snapshot: while 20 threads are moving money, every report it
 * produces must still be clean. A reconciler that read balances and entries separately would flag a
 * transfer that committed between the two reads as drift that does not exist.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=24") // 20 workers + the reconciler + headroom
@Import(IsolatedDatabaseConfig.class)
class ReconciliationUnderLoadTest {

    @Autowired ReconciliationService reconciliation;
    @Autowired PessimisticTransferService transfers;
    @Autowired LedgerRepository repository;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void everyReportDuringHeavyConcurrentTransfersIsClean() throws Exception {
        Pool pool = createPool(repository, transfers, 20, 3_000 * 1_000L);
        var workload = randomWorkload(pool, 3_000, 1_000, 99L);
        AtomicBoolean loadFinished = new AtomicBoolean();
        List<ReconciliationReport> reports = new ArrayList<>();

        CompletableFuture<Void> reconciler = CompletableFuture.runAsync(() -> {
            while (!loadFinished.get()) {
                reconciliation.reconcile().ifPresent(reports::add);
            }
        });
        RunResult result = run(transfers, workload, 20, "recon-load-" + UUID.randomUUID() + "-");
        loadFinished.set(true);
        reconciler.get(60, TimeUnit.SECONDS);

        assertThat(result.succeeded()).isEqualTo(3_000);
        assertThat(reports.size()).as("reconciliations that completed while load was running").isGreaterThanOrEqualTo(3);
        for (ReconciliationReport r : reports) {
            assertThat(r.isClean()).as("drift: %s, broken: %s", r.driftedAccounts(), r.brokenTransfers()).isTrue();
        }
        assertMoneyConserved(jdbc, pool);
    }
}
