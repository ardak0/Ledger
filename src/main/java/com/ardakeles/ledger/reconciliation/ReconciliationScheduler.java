package com.ardakeles.ledger.reconciliation;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.scheduling.annotation.Scheduled;

/**
 * Runs reconciliation periodically. {@code fixedDelay} (not fixedRate) so a slow run can never overlap the
 * next one. Disabled with {@code ledger.reconciliation.enabled=false}; the test profile does this so a
 * background scan cannot interfere with tests that deliberately corrupt data.
 */
@Configuration(proxyBeanMethods = false)
@EnableScheduling
@ConditionalOnProperty(prefix = "ledger.reconciliation", name = "enabled", havingValue = "true", matchIfMissing = true)
class ReconciliationScheduler {

    private static final Logger log = LoggerFactory.getLogger(ReconciliationScheduler.class);

    private final ReconciliationService reconciliation;

    ReconciliationScheduler(ReconciliationService reconciliation) {
        this.reconciliation = reconciliation;
    }

    @Scheduled(initialDelayString = "${ledger.reconciliation.initial-delay:PT1M}",
            fixedDelayString = "${ledger.reconciliation.interval:PT5M}")
    void run() {
        try {
            reconciliation.reconcile();
        } catch (RuntimeException e) {
            // Already counted as result=error by the service. Never let one failed run stop the schedule.
            log.error("reconciliation run failed", e);
        }
    }
}
