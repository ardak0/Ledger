package com.ardakeles.ledger.reconciliation;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ardakeles.ledger.IsolatedDatabaseConfig;
import io.micrometer.core.instrument.Counter;
import io.micrometer.core.instrument.MeterRegistry;
import java.time.Duration;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.test.annotation.DirtiesContext;

/**
 * Proves the job is actually scheduled -- forgetting @EnableScheduling, or a bad duration string, fails
 * silently in production. DirtiesContext closes this context afterwards: a live schedule left running in
 * a cached context would keep scanning (and holding the advisory lock) during unrelated tests.
 */
@SpringBootTest(properties = {
        "ledger.reconciliation.enabled=true",
        "ledger.reconciliation.initial-delay=PT0S",
        "ledger.reconciliation.interval=PT1S"})
@Import(IsolatedDatabaseConfig.class)
@DirtiesContext
class ReconciliationSchedulerTest {

    @Autowired MeterRegistry meters;

    @Test
    void theJobRunsOnItsOwnAndKeepsRunning() {
        await().atMost(Duration.ofSeconds(30)).untilAsserted(() ->
                assertThat(meters.find("ledger.reconciliation.runs").counters().stream().mapToDouble(Counter::count).sum())
                        .as("reconciliation runs triggered by the scheduler").isGreaterThanOrEqualTo(3));
    }
}
