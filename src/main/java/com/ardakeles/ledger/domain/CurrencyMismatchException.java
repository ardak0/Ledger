package com.ardakeles.ledger.domain;

/** Both accounts and the request must share one currency; v1 has no FX. */
public class CurrencyMismatchException extends LedgerException {

    public CurrencyMismatchException(String message) {
        super(message);
    }
}
