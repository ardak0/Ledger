package com.ardakeles.ledger.domain;

/**
 * A transfer violating the double-entry rule (at least 2 distinct accounts, exactly one currency, entries
 * sum to zero). The deferred trigger makes this impossible; seeing one means the trigger was bypassed.
 */
public record BrokenTransfer(long transferId, long sumMinor, long distinctAccounts, long distinctCurrencies) {
}
