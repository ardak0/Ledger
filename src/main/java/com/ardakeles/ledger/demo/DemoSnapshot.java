package com.ardakeles.ledger.demo;

import java.util.List;

/**
 * Everything the /demo page renders, sent as one JSON object per SSE event. The balances and both
 * sums come from a single SQL statement, so they are mutually consistent.
 */
public record DemoSnapshot(
        boolean ready,
        String mode,
        boolean running,
        int workers,
        List<AccountView> accounts,
        long poolSumMinor,
        long expectedPoolSumMinor,
        long globalSumMinor,
        Counters counters,
        List<RunSummary> history) {

    public record AccountView(long id, String name, long balanceMinor) {
    }

    /** Counters for the current (or most recent) run. */
    public record Counters(int run, int total, int completed, int failed, int deadlocked, int retried,
                           int insufficientFunds, boolean retryEnabled, long elapsedMillis) {
        static Counters none() {
            return new Counters(0, 0, 0, 0, 0, 0, 0, false, 0);
        }
    }

    public record RunSummary(int run, String mode, boolean retryEnabled, int total, int completed, int failed,
                             int deadlocked, int retried, long elapsedMillis) {
    }
}
