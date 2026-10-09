package com.ardakeles.ledger.domain;

/** Base class for expected business failures. Unchecked, so Spring rolls back the transaction. */
public abstract class LedgerException extends RuntimeException {

    protected LedgerException(String message) {
        super(message);
    }
}
