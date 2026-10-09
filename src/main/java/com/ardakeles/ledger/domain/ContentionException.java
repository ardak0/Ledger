package com.ardakeles.ledger.domain;

/**
 * A transfer could not be posted because concurrent updates kept invalidating it, and the bounded retry
 * budget ran out. Nothing was posted. The client may retry with the SAME idempotency key.
 */
public class ContentionException extends LedgerException {

    public ContentionException(int attempts) {
        super("transfer not posted: concurrent updates forced " + attempts + " attempts");
    }
}
