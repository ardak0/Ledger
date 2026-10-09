package com.ardakeles.ledger.domain;

public class InsufficientFundsException extends LedgerException {

    public InsufficientFundsException(long accountId, long balanceMinor, long amountMinor) {
        super("account " + accountId + " has " + balanceMinor + " but transfer needs " + amountMinor);
    }
}
