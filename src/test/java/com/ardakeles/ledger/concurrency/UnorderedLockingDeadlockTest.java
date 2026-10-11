package com.ardakeles.ledger.concurrency;

import static com.ardakeles.ledger.concurrency.LoadHarness.assertMoneyConserved;
import static com.ardakeles.ledger.concurrency.LoadHarness.assertPostedExactly;
import static com.ardakeles.ledger.concurrency.LoadHarness.createPool;
import static com.ardakeles.ledger.concurrency.LoadHarness.randomWorkload;
import static com.ardakeles.ledger.concurrency.LoadHarness.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.concurrency.LoadHarness.Pool;
import com.ardakeles.ledger.concurrency.LoadHarness.RunResult;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.LockingMode;
import com.ardakeles.ledger.service.PessimisticTransferService;
import com.ardakeles.ledger.service.TransferService;
import java.time.Duration;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.springframework.transaction.PlatformTransactionManager;

/**
 * NEGATIVE CONTROL -- this test turns ordered locking OFF on purpose, on its own TransferService instance
 * (the application's has no such switch).
 *
 * <p>It proves two things the main test cannot:
 * <ol>
 *   <li>the harness really can detect deadlocks, so "zero deadlocks" in the main test means
 *       something -- a test that cannot fail proves nothing;</li>
 *   <li>even when locking is broken, the ledger stays correct: deadlocked transactions roll back
 *       completely, so transfers fail but no money is created or destroyed and nothing is left
 *       half-posted. Safety here comes from transactions + constraints, not from the lock order;
 *       the lock order only buys liveness (transfers succeed instead of being killed).</li>
 * </ol>
 * A small account pool is used so opposing transfers (A->B vs B->A) collide almost every run.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@Import(TestcontainersConfig.class)
class UnorderedLockingDeadlockTest {

    static final int THREADS = 20;
    static final int TRANSFERS = 400;
    static final int ACCOUNTS = 6;
    static final long MAX_AMOUNT = 1_000;

    @Autowired LedgerRepository repository;
    @Autowired PlatformTransactionManager transactionManager;
    @Autowired NamedParameterJdbcTemplate jdbc;

    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void unorderedLockingDeadlocksButNeverCorruptsTheLedger() throws Exception {
        TransferService service = PessimisticTransferService.withLockingMode(repository, transactionManager,
                Duration.ofSeconds(5), new LockingMode(false));
        Pool pool = createPool(repository, service, ACCOUNTS, TRANSFERS * MAX_AMOUNT);
        List<TransferRequest> workload = randomWorkload(pool, TRANSFERS, MAX_AMOUNT, 7L);
        String prefix = "unordered-" + UUID.randomUUID() + "-";

        RunResult result = run(service, workload, THREADS, prefix);

        assertThat(result.deadlocks()).as("unordered locking must produce deadlocks").isPositive();
        assertThat(result.unexpected()).as("deadlocks are the only failure mode").isEmpty();
        assertThat(result.succeeded() + result.deadlocks()).isEqualTo(TRANSFERS);
        assertMoneyConserved(jdbc, pool);
        assertPostedExactly(jdbc, prefix, result.succeeded()); // a killed transfer left no rows behind
    }
}
