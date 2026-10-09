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
import com.ardakeles.ledger.service.TransferService;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Contract for any TransferService strategy. The test that matters most: many threads hammering overlapping accounts through the real
 * service and real Postgres, then checking that nothing was lost, created, or left half-done.
 * The connection pool is sized to the thread count so the database genuinely sees 20-way
 * concurrency instead of threads queuing for connections.
 */
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@Import(TestcontainersConfig.class)
abstract class ConcurrentTransfersContract {

    /** The implementation under test: the same assertions run against every TransferService strategy. */
    abstract TransferService service();

    static final int THREADS = 20;
    static final int TRANSFERS = 10_000;
    static final int ACCOUNTS = 50;
    static final long MAX_AMOUNT = 1_000;
    static final long SEED = 20261009L;

    @Autowired LedgerRepository repository;
    @Autowired NamedParameterJdbcTemplate jdbc;

    /**
     * How many of the 10,000 requests may legitimately be abandoned (clean ContentionException, nothing posted)
     * after exhausting a bounded retry budget. Zero for lock-based coordination, where waiting always ends in
     * success. Optimistic coordination discards and retries conflicting work, so a few unlucky transfers can
     * lose repeatedly; completion is probabilistic there, and 1% is a liveness bound, not a correctness one.
     */
    abstract int contentionTolerance();

    /**
     * Funded so that no transfer can legitimately fail -- even if every one of the 10,000 debited
     * the same account for the maximum amount -- which makes the expected completed count exactly
     * 10,000 instead of "some number".
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void tenThousandConcurrentTransfersCreateAndDestroyNothing() throws Exception {
        Pool pool = createPool(repository, service(), ACCOUNTS, TRANSFERS * MAX_AMOUNT);
        List<TransferRequest> workload = randomWorkload(pool, TRANSFERS, MAX_AMOUNT, SEED);
        String prefix = "load-" + UUID.randomUUID() + "-";

        RunResult result = run(service(), workload, THREADS, prefix);

        assertThat(result.unexpected()).as("unexpected failures").isEmpty();
        assertThat(result.deadlocks()).as("deadlock failures").isZero();
        assertThat(result.insufficientFunds()).isZero();
        assertThat(result.contention()).as("transfers abandoned after exhausting retries")
                .isLessThanOrEqualTo(contentionTolerance());
        assertThat(result.succeeded() + result.contention()).as("every request ended posted or abandoned")
                .isEqualTo(TRANSFERS);
        assertMoneyConserved(jdbc, pool);
        assertPostedExactly(jdbc, prefix, result.succeeded());
    }

    /**
     * Same workload against tiny balances, so a large share of transfers MUST be rejected. This
     * exercises the funds check under contention: if the check read a stale balance, some
     * transfers would pass it and then trip the CHECK constraint, surfacing as a raw constraint
     * error instead of InsufficientFunds. With the check made under the row lock, every failure is
     * a clean business rejection.
     */
    @Test
    @Timeout(value = 180, unit = TimeUnit.SECONDS)
    void contendedFundsCheckRejectsCleanlyNeverViaConstraintViolation() throws Exception {
        Pool pool = createPool(repository, service(), ACCOUNTS, 1_000);
        List<TransferRequest> workload = randomWorkload(pool, TRANSFERS, 600, SEED);
        String prefix = "scarce-" + UUID.randomUUID() + "-";

        RunResult result = run(service(), workload, THREADS, prefix);

        assertThat(result.unexpected()).as("failures other than InsufficientFunds").isEmpty();
        assertThat(result.deadlocks()).isZero();
        assertThat(result.contention()).isLessThanOrEqualTo(contentionTolerance());
        assertThat(result.succeeded() + result.insufficientFunds() + result.contention()).isEqualTo(TRANSFERS);
        assertThat(result.insufficientFunds()).as("scenario must actually hit the funds limit").isPositive();
        assertThat(result.succeeded()).as("and must still complete some transfers").isPositive();
        assertMoneyConserved(jdbc, pool);
        assertPostedExactly(jdbc, prefix, result.succeeded());
    }
}
