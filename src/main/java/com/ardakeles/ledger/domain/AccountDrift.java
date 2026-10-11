package com.ardakeles.ledger.domain;

/** An account whose cached balance disagrees with the sum of its entries. Entries are the truth. */
public record AccountDrift(long accountId, long cachedMinor, long computedMinor) {

    /** Positive: the cache claims more money than the entries support. */
    public long differenceMinor() {
        return cachedMinor - computedMinor;
    }
}
