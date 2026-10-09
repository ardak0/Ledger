package com.ardakeles.ledger.domain;

/** The request is well-formed but can never be a valid transfer (e.g. non-positive amount). */
public class InvalidTransferException extends LedgerException {

    public InvalidTransferException(String message) {
        super(message);
    }
}
