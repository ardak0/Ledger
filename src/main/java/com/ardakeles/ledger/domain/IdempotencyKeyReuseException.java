package com.ardakeles.ledger.domain;

/** The idempotency key was already used with a different request payload: a client bug. */
public class IdempotencyKeyReuseException extends LedgerException {

    public IdempotencyKeyReuseException(String idempotencyKey) {
        super("idempotency key '" + idempotencyKey + "' was already used with a different request");
    }
}
