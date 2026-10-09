package com.ardakeles.ledger.domain;

public class AccountNotFoundException extends LedgerException {

    public AccountNotFoundException(long accountId) {
        super("account " + accountId + " not found");
    }
}
