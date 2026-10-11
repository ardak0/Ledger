package com.ardakeles.ledger.domain;

import java.util.Map;

/**
 * Balances of selected accounts plus the sum over every account in one currency, read in a single SQL
 * statement and therefore from one consistent snapshot of committed data. {@code balances} keeps
 * ascending id order.
 */
public record BalanceSnapshot(Map<Long, Long> balances, long currencySumMinor) {
}
