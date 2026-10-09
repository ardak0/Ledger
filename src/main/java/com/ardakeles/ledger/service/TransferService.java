package com.ardakeles.ledger.service;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountNotFoundException;
import com.ardakeles.ledger.domain.CurrencyMismatchException;
import com.ardakeles.ledger.domain.InsufficientFundsException;
import com.ardakeles.ledger.domain.Transfer;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class TransferService {

    private final LedgerRepository repository;

    public TransferService(LedgerRepository repository) {
        this.repository = repository;
    }

    /**
     * Posts a transfer: one transfer row, a debit entry and a credit entry, and the matching
     * balance-cache updates, all in one transaction. Any exception rolls everything back.
     *
     * <p>STEP 2 ONLY -- NOT CONCURRENCY-SAFE. Accounts are read without locks, so two concurrent
     * transfers can both pass the funds check against the same stale balance. The CHECK
     * constraint still prevents an actual overdraft, but it surfaces as a raw constraint error
     * instead of InsufficientFundsException. Step 5 replaces the reads with ordered row locks.
     */
    @Transactional
    public Transfer execute(String idempotencyKey, TransferRequest request) {
        Account from = repository.findAccount(request.fromAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.fromAccountId()));
        Account to = repository.findAccount(request.toAccountId())
                .orElseThrow(() -> new AccountNotFoundException(request.toAccountId()));

        if (from.currency() != request.currency() || to.currency() != request.currency()) {
            throw new CurrencyMismatchException("request is in " + request.currency() + " but accounts are "
                    + from.currency() + " and " + to.currency());
        }
        if (!from.canDebit(request.amountMinor())) {
            throw new InsufficientFundsException(from.id(), from.balanceMinor(), request.amountMinor());
        }

        Transfer transfer = repository.insertTransfer(idempotencyKey, request);
        // amountMinor > 0 is guaranteed by TransferRequest, so negating it cannot overflow.
        repository.insertEntry(transfer.id(), from.id(), -request.amountMinor(), request.currency());
        repository.insertEntry(transfer.id(), to.id(), request.amountMinor(), request.currency());
        repository.applyBalanceDelta(from.id(), -request.amountMinor());
        repository.applyBalanceDelta(to.id(), request.amountMinor());
        return transfer;
    }
}
