package com.ardakeles.ledger.service;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountNotFoundException;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.EntryPage;
import com.ardakeles.ledger.domain.LedgerEntry;
import com.ardakeles.ledger.repository.LedgerRepository;
import java.util.List;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

@Service
public class AccountService {

    private final LedgerRepository repository;

    public AccountService(LedgerRepository repository) {
        this.repository = repository;
    }

    /** Only ever creates ordinary accounts. System accounts are not reachable from the API. */
    @Transactional
    public Account create(Currency currency) {
        return repository.createAccount(currency);
    }

    @Transactional(readOnly = true)
    public Account get(long id) {
        return repository.findAccount(id).orElseThrow(() -> new AccountNotFoundException(id));
    }

    @Transactional(readOnly = true)
    public EntryPage entries(long accountId, long afterId, int limit) {
        get(accountId); // an unknown account is a 404, not an empty statement
        // Fetch one extra row: its presence tells us another page exists without a COUNT query.
        List<LedgerEntry> rows = repository.findEntries(accountId, afterId, limit + 1);
        boolean hasMore = rows.size() > limit;
        List<LedgerEntry> page = hasMore ? rows.subList(0, limit) : rows;
        Long nextAfter = hasMore ? page.get(page.size() - 1).id() : null;
        return new EntryPage(List.copyOf(page), nextAfter);
    }
}
