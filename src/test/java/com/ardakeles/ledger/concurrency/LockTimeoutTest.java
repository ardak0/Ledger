package com.ardakeles.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
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
import java.util.concurrent.TimeUnit;
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

/**
 * A stuck lock holder must not block transfers forever. Another connection holds an account's row lock and
 * never lets go (until the test ends); a transfer touching that account must give up after
 * {@code ledger.lock-timeout}, roll back completely (nothing posted, key not burned), and reach the client as
 * a retryable 503 -- for both strategies, since the optimistic one's version-checked UPDATE also waits on
 * row locks.
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
            mvc.perform(post("/transfers").header("Idempotency-Key", "lock-timeout-" + UUID.randomUUID())
                            .contentType(MediaType.APPLICATION_JSON)
                            .content("{\"fromAccountId\":%d,\"toAccountId\":%d,\"amountMinor\":10,\"currency\":\"USD\"}"
                                    .formatted(from.id(), to.id())))
                    .andExpect(status().isServiceUnavailable())
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
            assertThatThrownBy(() -> service.execute(key, request)).isInstanceOf(ConcurrencyFailureException.class);
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
