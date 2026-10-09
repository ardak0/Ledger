package com.ardakeles.ledger.domain;

import java.time.OffsetDateTime;

/** What the transfer table remembers about an idempotency key: enough to detect a mismatched replay. */
public record StoredTransfer(long id, byte[] requestHash, OffsetDateTime createdAt) {
}
