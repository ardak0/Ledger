package com.ardakeles.ledger.domain;

import java.time.Instant;
import java.util.List;

/** The outcome of one reconciliation run, computed from a single consistent snapshot of the database. */
public record ReconciliationReport(
        Instant startedAt,
        long durationMillis,
        LedgerCounts checked,
        long driftedAccountCount,
        List<AccountDrift> driftedAccounts,
        long brokenTransferCount,
        List<BrokenTransfer> brokenTransfers) {

    public boolean isClean() {
        return driftedAccountCount == 0 && brokenTransferCount == 0;
    }
}
