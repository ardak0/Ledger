package com.ardakeles.ledger.domain;

/** A snapshot of an account row. {@code balanceMinor} is the cached balance; entries are the truth. */
public record Account(long id, Currency currency, boolean system, long balanceMinor, long version) {

    /** Non-system accounts may not go negative; system accounts (funding sources) may. */
    public boolean canDebit(long amountMinor) {
        return system || balanceMinor >= amountMinor;
    }
}
