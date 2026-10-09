package com.ardakeles.ledger.domain;

import java.time.OffsetDateTime;

/** A posted transfer: the request that was executed plus the identity the ledger gave it. */
public record Transfer(long id, String idempotencyKey, TransferRequest request, OffsetDateTime createdAt) {
}
