package com.ardakeles.ledger.domain;

import java.time.OffsetDateTime;

/** One signed line of the ledger: the change an entry made to one account's balance. */
public record LedgerEntry(long id, long transferId, long accountId, long amountMinor, Currency currency,
                          OffsetDateTime createdAt) {
}
