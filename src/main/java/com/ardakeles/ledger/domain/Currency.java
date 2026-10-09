package com.ardakeles.ledger.domain;

/**
 * Supported currencies. Must match the allowlist CHECK in the account table (V1 migration);
 * a test creates an account for every value so drift between the two fails the build.
 * All of these have 2 decimal places, so one minor unit is always 1/100.
 */
public enum Currency {
    USD, EUR, GBP, TRY
}
