package com.ardakeles.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.fail;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.OptimisticTransferService;
import com.ardakeles.ledger.service.PessimisticTransferService;
import com.ardakeles.ledger.service.TransferService;
import java.sql.Connection;
import java.sql.Statement;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutionException;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.TimeoutException;
import javax.sql.DataSource;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.autoconfigure.web.servlet.AutoConfigureMockMvc;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.dao.ConcurrencyFailureException;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;

/**
 * A stuck lock holder must not block transfers forever. Another connection holds an account's row lock and
 * never lets go (until the test ends); a transfer touching that account must give up after
 * {@code ledger.lock-timeout}, roll back completely (nothing posted, key not burned), and reach the client as
 * a retryable 503 -- for both strategies, since the optimistic one's version-checked UPDATE also waits on
 * row locks.
 *
 * <p>If the timeout ever stopped working, the call under test would block forever, and JUnit's @Timeout cannot
 * interrupt a thread stuck in a JDBC socket read: the build would hang instead of failing. So the call runs on a
 * worker thread with a bounded wait; if the wait expires, the test releases the stuck lock itself (so nothing is
 * left blocked) and fails with a clear message.
 */
@SpringBootTest(properties = "ledger.lock-timeout=300ms")
@AutoConfigureMockMvc
@Import(TestcontainersConfig.class)
@Timeout(value = 60, unit = TimeUnit.SECONDS)
class LockTimeoutTest {

    /** Generous upper bound for a 300ms timeout: proves the wait ended because of it, not by luck. */
    static final Duration GAVE_UP_WITHIN = Duration.ofSeconds(3);

    @Autowired PessimisticTransferService pessimistic;
    @Autowired OptimisticTransferService optimistic;
    @Autowired LedgerRepository repository;
    @Autowired DataSource dataSource;
    @Autowired JdbcTemplate jdbc;
    @Autowired MockMvc mvc;

    Account from;
    Account to;

    @BeforeEach
    void fundedPair() {
        Account funding = repository.createSystemAccount(Currency.USD);
        from = repository.createAccount(Currency.USD);
        to = repository.createAccount(Currency.USD);
        pessimistic.execute("fund-" + UUID.randomUUID(), new TransferRequest(funding.id(), from.id(), 1_000, Currency.USD));
    }

    @Test
    void pessimisticGivesUpOnAStuckLockAndPostsNothing() throws Exception {
        assertGivesUpAndPostsNothing(pessimistic);
    }

    @Test
    void optimisticGivesUpOnAStuckLockAndPostsNothing() throws Exception {
        assertGivesUpAndPostsNothing(optimistic);
    }

    @Test
    void aLockTimeoutReachesTheClientAsARetryable503() throws Exception {
        try (Connection holder = holdRowLock(to.id())) {
            ResultActions response = completesWithin(holder, () ->
                    mvc.perform(post("/transfers").header("Idempotency-Key", "lock-timeout-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amountMinor\":10,\"currency\":\"USD\"}"
                                    .formatted(from.id(), to.id()))));
            response.andExpect(status().isServiceUnavailable())
                    .andExpect(header().string("Retry-After", "1"))
                    .andExpect(jsonPath("$.code").value("TRANSIENT_FAILURE"));
            holder.rollback();
        }
    }

    private void assertGivesUpAndPostsNothing(TransferService service) throws Exception {
        String key = "lock-timeout-" + UUID.randomUUID();
        TransferRequest request = new TransferRequest(from.id(), to.id(), 10, Currency.USD);

        try (Connection holder = holdRowLock(to.id())) {
            long start = System.nanoTime();
            assertThat(failureOf(holder, () -> service.execute(key, request))).isInstanceOf(ConcurrencyFailureException.class);
            assertThat(Duration.ofNanos(System.nanoTime() - start)).isLessThan(GAVE_UP_WITHIN);
            holder.rollback();
        }

        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE idempotency_key = ?", Integer.class, key))
                .as("the timed-out attempt rolled back, claim included").isZero();
        assertThat(balance(from.id())).isEqualTo(1_000);
        // The key was not burned: once the lock is gone, the same key posts normally.
        assertThat(service.execute(key, request).replayed()).isFalse();
        assertThat(balance(from.id())).isEqualTo(990);
    }

    /**
     * Runs {@code call} on a worker thread and returns its result, or fails the test if it is still running after
     * {@link #GAVE_UP_WITHIN}. On expiry the stuck lock is released first, so the abandoned worker can finish.
     */
    private <T> T completesWithin(Connection holder, Callable<T> call) throws Exception {
        ExecutorService worker = Executors.newSingleThreadExecutor();
        Future<T> future = worker.submit(call);
        try {
            return future.get(GAVE_UP_WITHIN.toMillis(), TimeUnit.MILLISECONDS);
        } catch (TimeoutException e) {
            holder.rollback();
            return fail("still waiting on a stuck lock after %s: the lock timeout is not being applied", GAVE_UP_WITHIN);
        } finally {
            worker.shutdownNow();
        }
    }

    /** Like {@link #completesWithin}, but the call is expected to throw; returns what it threw. */
    private Throwable failureOf(Connection holder, Callable<?> call) throws Exception {
        try {
            completesWithin(holder, call);
        } catch (ExecutionException e) {
            return e.getCause();
        }
        return fail("expected the transfer to fail with a lock timeout, but it succeeded");
    }

    /** A connection outside any Spring transaction that takes the row lock and keeps it until rolled back. */
    private Connection holdRowLock(long accountId) throws Exception {
        Connection c = dataSource.getConnection();
        c.setAutoCommit(false);
        try (Statement s = c.createStatement()) {
            s.execute("SELECT 1 FROM account WHERE id = " + accountId + " FOR UPDATE");
        }
        return c;
    }

    private long balance(long accountId) {
        return jdbc.queryForObject("SELECT balance_minor FROM account WHERE id = ?", Long.class, accountId);
    }
}
