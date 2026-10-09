package com.ardakeles.ledger.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.IdempotencyKeyReuseException;
import com.ardakeles.ledger.domain.InsufficientFundsException;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.domain.TransferResult;
import com.ardakeles.ledger.repository.LedgerRepository;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.Callable;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;

/**
 * Idempotency against real Postgres. The concurrent tests are the point: a check-then-insert
 * implementation passes every sequential test here and fails only when requests truly overlap.
 * Thread counts stay at or below Hikari's default pool size (10) so the test measures idempotency,
 * not connection-pool queuing.
 */
@SpringBootTest
@Import(TestcontainersConfig.class)
class IdempotencyTest {

    static final int THREADS = 8;

    @Autowired TransferService service;
    @Autowired LedgerRepository repository;
    @Autowired JdbcTemplate jdbc;

    @Test
    void replayWithSamePayloadReturnsTheOriginalAndMovesMoneyOnce() {
        Account alice = funded(1_000);
        Account bob = repository.createAccount(Currency.USD);
        String key = key();
        var request = new TransferRequest(alice.id(), bob.id(), 300, Currency.USD);

        TransferResult first = service.execute(key, request);
        TransferResult second = service.execute(key, request);

        assertThat(first.replayed()).isFalse();
        assertThat(second.replayed()).isTrue();
        assertThat(second.transfer()).isEqualTo(first.transfer()); // same id, same createdAt, same request
        assertThat(balance(alice)).isEqualTo(700);
        assertThat(balance(bob)).isEqualTo(300);
        assertThat(entriesFor(key)).isEqualTo(2);
    }

    @Test
    void sameKeyWithDifferentPayloadIsRefusedAndChangesNothing() {
        Account alice = funded(1_000);
        Account bob = repository.createAccount(Currency.USD);
        String key = key();
        service.execute(key, new TransferRequest(alice.id(), bob.id(), 300, Currency.USD));

        assertThatThrownBy(() -> service.execute(key, new TransferRequest(alice.id(), bob.id(), 301, Currency.USD)))
                .isInstanceOf(IdempotencyKeyReuseException.class);

        assertThat(balance(alice)).isEqualTo(700);
        assertThat(balance(bob)).isEqualTo(300);
        assertThat(entriesFor(key)).isEqualTo(2);
    }

    /** A failed attempt rolls back its claim, so the key stays usable once the cause is fixed. */
    @Test
    void failedAttemptDoesNotBurnTheKey() {
        Account alice = funded(100);
        Account bob = repository.createAccount(Currency.USD);
        String key = key();
        var request = new TransferRequest(alice.id(), bob.id(), 150, Currency.USD);

        assertThatThrownBy(() -> service.execute(key, request)).isInstanceOf(InsufficientFundsException.class);
        assertThat(transfersWithKey(key)).isZero();

        fund(alice, 100); // now 200
        TransferResult retry = service.execute(key, request);

        assertThat(retry.replayed()).isFalse(); // a genuine first success, not a replay
        assertThat(balance(alice)).isEqualTo(50);
        assertThat(balance(bob)).isEqualTo(150);
    }

    @Test
    void concurrentIdenticalRequestsExecuteOnceAndAllGetTheSameTransfer() throws Exception {
        Account alice = funded(10_000);
        Account bob = repository.createAccount(Currency.USD);
        String key = key();
        var request = new TransferRequest(alice.id(), bob.id(), 250, Currency.USD);

        List<Outcome> outcomes = runConcurrently(THREADS, i -> () -> service.execute(key, request));

        assertThat(outcomes).allSatisfy(o -> assertThat(o.error()).as("no request may fail").isNull());
        assertThat(outcomes.stream().filter(o -> !o.result().replayed())).hasSize(1); // exactly one winner
        assertThat(outcomes.stream().map(o -> o.result().transfer().id()).distinct()).hasSize(1);
        assertThat(balance(alice)).isEqualTo(9_750); // moved once, not eight times
        assertThat(balance(bob)).isEqualTo(250);
        assertThat(entriesFor(key)).isEqualTo(2);
        assertThat(transfersWithKey(key)).isEqualTo(1);
    }

    @Test
    void concurrentRequestsWithTwoDifferentPayloadsOnOneKeyHaveExactlyOneWinningPayload() throws Exception {
        Account alice = funded(10_000);
        Account bob = repository.createAccount(Currency.USD);
        String key = key();
        var payloadA = new TransferRequest(alice.id(), bob.id(), 100, Currency.USD);
        var payloadB = new TransferRequest(alice.id(), bob.id(), 200, Currency.USD);

        // even threads send A, odd threads send B
        List<Outcome> outcomes = runConcurrently(THREADS,
                i -> () -> service.execute(key, i % 2 == 0 ? payloadA : payloadB));

        List<Outcome> winners = outcomes.stream().filter(o -> o.result() != null && !o.result().replayed()).toList();
        assertThat(winners).hasSize(1);
        long winnerAmount = winners.get(0).result().transfer().request().amountMinor();
        long winnerId = winners.get(0).result().transfer().id();

        long expectedRejected = THREADS / 2; // every sender of the losing payload
        List<Outcome> rejected = outcomes.stream().filter(o -> o.error() != null).toList();
        assertThat(rejected).hasSize((int) expectedRejected);
        assertThat(rejected).allSatisfy(o -> assertThat(o.error()).isInstanceOf(IdempotencyKeyReuseException.class));
        outcomes.stream().filter(o -> o.result() != null).forEach(o -> {
            assertThat(o.result().transfer().id()).isEqualTo(winnerId);
            assertThat(o.result().transfer().request().amountMinor()).isEqualTo(winnerAmount);
        });
        assertThat(balance(alice)).isEqualTo(10_000 - winnerAmount); // only the winning payload moved money
        assertThat(balance(bob)).isEqualTo(winnerAmount);
        assertThat(entriesFor(key)).isEqualTo(2);
    }

    // ---- concurrency harness ----

    record Outcome(TransferResult result, Throwable error) {
    }

    /** Runs {@code threads} tasks released at the same instant by a latch, so their transactions truly overlap. */
    private List<Outcome> runConcurrently(int threads, java.util.function.IntFunction<Callable<TransferResult>> taskFor)
            throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            CountDownLatch ready = new CountDownLatch(threads);
            CountDownLatch go = new CountDownLatch(1);
            List<Future<Outcome>> futures = new ArrayList<>();
            for (int i = 0; i < threads; i++) {
                Callable<TransferResult> task = taskFor.apply(i);
                futures.add(pool.submit(() -> {
                    ready.countDown();
                    go.await();
                    try {
                        return new Outcome(task.call(), null);
                    } catch (Throwable t) {
                        return new Outcome(null, t);
                    }
                }));
            }
            ready.await();
            go.countDown();
            List<Outcome> outcomes = new ArrayList<>();
            for (Future<Outcome> f : futures) {
                outcomes.add(f.get());
            }
            return outcomes;
        } finally {
            pool.shutdownNow();
        }
    }

    // ---- helpers ----

    private Account funded(long amount) {
        Account account = repository.createAccount(Currency.USD);
        fund(account, amount);
        return account;
    }

    private void fund(Account account, long amount) {
        Account funding = repository.createSystemAccount(Currency.USD);
        service.execute(key(), new TransferRequest(funding.id(), account.id(), amount, Currency.USD));
    }

    private long balance(Account a) {
        return jdbc.queryForObject("SELECT balance_minor FROM account WHERE id = ?", Long.class, a.id());
    }

    private int transfersWithKey(String key) {
        return jdbc.queryForObject("SELECT count(*) FROM transfer WHERE idempotency_key = ?", Integer.class, key);
    }

    private int entriesFor(String key) {
        return jdbc.queryForObject(
                "SELECT count(*) FROM entry e JOIN transfer t ON t.id = e.transfer_id WHERE t.idempotency_key = ?",
                Integer.class, key);
    }

    private static String key() {
        return UUID.randomUUID().toString();
    }
}
