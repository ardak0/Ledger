package com.ardakeles.ledger.api;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.EntryPage;
import com.ardakeles.ledger.domain.LedgerEntry;
import com.ardakeles.ledger.service.AccountService;
import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.net.URI;
import java.time.OffsetDateTime;
import java.util.List;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.server.ResponseStatusException;

@RestController
class AccountController {

    static final int MAX_PAGE_SIZE = 200;

    private final AccountService accounts;

    AccountController(AccountService accounts) {
        this.accounts = accounts;
    }

    /**
     * The request deliberately has no way to say "system account": unknown properties are rejected
     * (see application.yml), so {"isSystem": true} is a 400, not a silently ignored field.
     */
    record CreateAccountRequest(@NotNull Currency currency) {
    }

    record AccountResponse(long id, Currency currency, long balanceMinor, OffsetDateTime createdAt) {
        static AccountResponse from(Account a) {
            return new AccountResponse(a.id(), a.currency(), a.balanceMinor(), a.createdAt());
        }
    }

    record BalanceResponse(long accountId, Currency currency, long balanceMinor) {
    }

    record EntryResponse(long id, long transferId, long amountMinor, Currency currency, OffsetDateTime createdAt) {
        static EntryResponse from(LedgerEntry e) {
            return new EntryResponse(e.id(), e.transferId(), e.amountMinor(), e.currency(), e.createdAt());
        }
    }

    /** {@code nextAfter} is the value to pass as {@code ?after=} for the next page; null on the last page. */
    record EntryPageResponse(List<EntryResponse> entries, Long nextAfter) {
        static EntryPageResponse from(EntryPage p) {
            return new EntryPageResponse(p.entries().stream().map(EntryResponse::from).toList(), p.nextAfter());
        }
    }

    @PostMapping("/accounts")
    ResponseEntity<AccountResponse> create(@Valid @RequestBody CreateAccountRequest request) {
        Account account = accounts.create(request.currency());
        return ResponseEntity.created(URI.create("/accounts/" + account.id())).body(AccountResponse.from(account));
    }

    @GetMapping("/accounts/{id}/balance")
    BalanceResponse balance(@PathVariable long id) {
        Account account = accounts.get(id);
        return new BalanceResponse(account.id(), account.currency(), account.balanceMinor());
    }

    @GetMapping("/accounts/{id}/entries")
    EntryPageResponse entries(@PathVariable long id,
                              @RequestParam(defaultValue = "0") long after,
                              @RequestParam(defaultValue = "50") int limit) {
        if (after < 0) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "after must be >= 0");
        }
        // Reject rather than silently clamp: a client asking for 10,000 should be told, not misled.
        if (limit < 1 || limit > MAX_PAGE_SIZE) {
            throw new ResponseStatusException(HttpStatus.BAD_REQUEST, "limit must be between 1 and " + MAX_PAGE_SIZE);
        }
        return EntryPageResponse.from(accounts.entries(id, after, limit));
    }
}
