package com.ardakeles.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.AccountNotFoundException;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.CurrencyMismatchException;
import com.ardakeles.ledger.domain.InsufficientFundsException;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import java.util.List;
import java.util.UUID;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Contract for any TransferService: single-threaded behaviour of the transfer. Reads are done with raw SQL on purpose so the
 * assertions do not depend on the repository code being tested. Each test creates its own
 * accounts and never deletes anything (the ledger is append-only), so tests are independent.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
abstract class TransferServiceContract {

    /** The implementation under test: the same assertions run against every TransferService strategy. */
    abstract TransferService service();

    @Autowired LedgerRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void fundingThenTransferMovesMoneyAndKeepsCacheEqualToEntries() {
        Account funding = repository.createSystemAccount(Currency.USD);
        Account alice = repository.createAccount(Currency.USD);
        Account bob = repository.createAccount(Currency.USD);

        service().execute(key(), new TransferRequest(funding.id(), alice.id(), 10_000, Currency.USD));
        String key = key();
        service().execute(key, new TransferRequest(alice.id(), bob.id(), 2_500, Currency.USD));

        assertThat(balance(funding)).isEqualTo(-10_000); // system account went negative to create the funds
        assertThat(balance(alice)).isEqualTo(7_500);
        assertThat(balance(bob)).isEqualTo(2_500);
        // entry is the source of truth: the cached balance must equal the sum of entries
        for (Account a : List.of(funding, alice, bob)) {
            assertThat(balance(a)).as("cache vs entries for account %d", a.id()).isEqualTo(entrySum(a));
        }
        // money is conserved: nothing created or destroyed across the three accounts
        assertThat(balance(funding) + balance(alice) + balance(bob)).isZero();
        // the second transfer debited alice and credited bob, with the right signs
        assertThat(jdbc.queryForList(
                        "SELECT e.account_id || ':' || e.amount_minor FROM entry e JOIN transfer t ON t.id = e.transfer_id"
                                + " WHERE t.idempotency_key = ? ORDER BY e.amount_minor",
                        String.class, key))
                .containsExactly(alice.id() + ":-2500", bob.id() + ":2500");
        // alice was touched by two transfers, so her version moved twice
        assertThat(jdbc.queryForObject("SELECT version FROM account WHERE id = ?", Long.class, alice.id())).isEqualTo(2);
    }

    @Test
    void transferringTheWholeBalanceIsAllowed() {
        Account alice = funded(100);
        Account bob = repository.createAccount(Currency.USD);

        service().execute(key(), new TransferRequest(alice.id(), bob.id(), 100, Currency.USD));

        assertThat(balance(alice)).isZero();
        assertThat(balance(bob)).isEqualTo(100);
    }

    @Test
    void oneUnitOverTheBalanceIsRejectedAndChangesNothing() {
        Account alice = funded(100);
        Account bob = repository.createAccount(Currency.USD);
        String key = key();

        assertThatThrownBy(() -> service().execute(key, new TransferRequest(alice.id(), bob.id(), 101, Currency.USD)))
                .isInstanceOf(InsufficientFundsException.class);

        assertThat(balance(alice)).isEqualTo(100);
        assertThat(balance(bob)).isZero();
        assertThat(jdbc.queryForObject("SELECT count(*) FROM transfer WHERE idempotency_key = ?", Integer.class, key)).isZero();
    }

    @Test
    void unknownAccountIsRejected() {
        Account alice = funded(100);

        assertThatThrownBy(() -> service().execute(key(), new TransferRequest(alice.id(), Long.MAX_VALUE, 10, Currency.USD)))
                .isInstanceOf(AccountNotFoundException.class);
    }

    @Test
    void accountsInDifferentCurrenciesAreRejected() {
        Account usd = funded(100);
        Account eur = repository.createAccount(Currency.EUR);

        assertThatThrownBy(() -> service().execute(key(), new TransferRequest(usd.id(), eur.id(), 10, Currency.USD)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    @Test
    void requestCurrencyMustMatchTheAccountsCurrency() {
        Account alice = funded(100);
        Account bob = repository.createAccount(Currency.USD);

        assertThatThrownBy(() -> service().execute(key(), new TransferRequest(alice.id(), bob.id(), 10, Currency.EUR)))
                .isInstanceOf(CurrencyMismatchException.class);
    }

    /** Guards against the Java enum and the DB's currency CHECK allowlist drifting apart. */
    @Test
    void everyCurrencyInTheEnumIsAcceptedByTheDatabase() {
        for (Currency c : Currency.values()) {
            assertThat(repository.createAccount(c).currency()).isEqualTo(c);
        }
    }

    // ---- helpers ----

    /** A non-system USD account holding {@code amount}, funded the only legitimate way: by transfer. */
    private Account funded(long amount) {
        Account funding = repository.createSystemAccount(Currency.USD);
        Account account = repository.createAccount(Currency.USD);
        service().execute(key(), new TransferRequest(funding.id(), account.id(), amount, Currency.USD));
        return account;
    }

    private long balance(Account a) {
        return jdbc.queryForObject("SELECT balance_minor FROM account WHERE id = ?", Long.class, a.id());
    }

    private long entrySum(Account a) {
        return jdbc.queryForObject("SELECT COALESCE(sum(amount_minor), 0) FROM entry WHERE account_id = ?", Long.class, a.id());
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
