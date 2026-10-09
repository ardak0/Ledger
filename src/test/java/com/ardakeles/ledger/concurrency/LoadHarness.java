package com.ardakeles.ledger.concurrency;

import static org.assertj.core.api.Assertions.assertThat;

import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.InsufficientFundsException;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.TransferService;
import java.sql.SQLException;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.Queue;
import java.util.SplittableRandom;
import java.util.UUID;
import java.util.concurrent.ConcurrentLinkedQueue;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * Shared machinery for the concurrency tests: build a funded pool of accounts, fire a reproducible
 * random workload from many threads, classify every outcome, and check the ledger's invariants
 * using raw SQL that does not depend on the code under test.
 */
final class LoadHarness {

    private static final Logger log = LoggerFactory.getLogger(LoadHarness.class);

    /** SQLSTATE Postgres reports for a transaction it killed to break a deadlock. */
    private static final String DEADLOCK_DETECTED = "40P01";

    private LoadHarness() {
    }

    record Pool(Account funding, List<Account> accounts, long initialPerAccount) {
        long initialTotal() {
            return initialPerAccount * accounts.size();
        }

        List<Long> ids() {
            return accounts.stream().map(Account::id).toList();
        }
    }

    record RunResult(int succeeded, int insufficientFunds, int deadlocks, List<Throwable> unexpected, Duration elapsed) {
        int total() {
            return succeeded + insufficientFunds + deadlocks + unexpected.size();
        }
    }

    /** Creates {@code size} accounts and funds each from a system account, the only legitimate way to get money. */
    static Pool createPool(LedgerRepository repository, TransferService service, int size, long initialPerAccount) {
        Account funding = repository.createSystemAccount(Currency.USD);
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < size; i++) {
            Account account = repository.createAccount(Currency.USD);
            service.execute("fund-" + UUID.randomUUID(),
                    new TransferRequest(funding.id(), account.id(), initialPerAccount, Currency.USD));
            accounts.add(account);
        }
        return new Pool(funding, accounts, initialPerAccount);
    }

    /**
     * A fixed-seed workload: the same seed always yields the same list of transfers, so a failure can
     * be re-run with an identical request set. (Thread interleaving is of course not reproducible.)
     */
    static List<TransferRequest> randomWorkload(Pool pool, int count, long maxAmount, long seed) {
        SplittableRandom random = new SplittableRandom(seed);
        int n = pool.accounts().size();
        List<TransferRequest> requests = new ArrayList<>(count);
        for (int i = 0; i < count; i++) {
            int from = random.nextInt(n);
            int to = random.nextInt(n - 1);
            if (to >= from) {
                to++; // uniform over the other n-1 accounts, never equal to from
            }
            long amount = 1 + random.nextLong(maxAmount);
            requests.add(new TransferRequest(pool.accounts().get(from).id(), pool.accounts().get(to).id(), amount, Currency.USD));
        }
        return requests;
    }

    static RunResult run(TransferService service, List<TransferRequest> workload, int threads, String keyPrefix)
            throws InterruptedException {
        AtomicInteger succeeded = new AtomicInteger();
        AtomicInteger insufficient = new AtomicInteger();
        AtomicInteger deadlocks = new AtomicInteger();
        Queue<Throwable> unexpected = new ConcurrentLinkedQueue<>();

        ExecutorService executor = Executors.newFixedThreadPool(threads);
        long start = System.nanoTime();
        for (int i = 0; i < workload.size(); i++) {
            String key = keyPrefix + i;
            TransferRequest request = workload.get(i);
            executor.submit(() -> {
                try {
                    service.execute(key, request);
                    succeeded.incrementAndGet();
                } catch (InsufficientFundsException e) {
                    insufficient.incrementAndGet();
                } catch (Throwable t) {
                    if (isDeadlock(t)) {
                        deadlocks.incrementAndGet();
                    } else {
                        unexpected.add(t);
                    }
                }
            });
        }
        executor.shutdown();
        boolean finished = executor.awaitTermination(150, TimeUnit.SECONDS);
        Duration elapsed = Duration.ofNanos(System.nanoTime() - start);
        executor.shutdownNow();
        assertThat(finished).as("workload finished before the timeout (a hang means a lock was never released)").isTrue();

        RunResult result = new RunResult(succeeded.get(), insufficient.get(), deadlocks.get(),
                List.copyOf(unexpected), elapsed);
        log.info("LOAD threads={} requests={} succeeded={} insufficientFunds={} deadlocks={} other={} elapsed={}ms ({}/s)",
                threads, workload.size(), result.succeeded(), result.insufficientFunds(), result.deadlocks(),
                result.unexpected().size(), elapsed.toMillis(),
                Math.round(workload.size() * 1000.0 / Math.max(1, elapsed.toMillis())));
        return result;
    }

    /** True if a Postgres deadlock-victim error appears anywhere in the cause chain. */
    static boolean isDeadlock(Throwable t) {
        for (Throwable c = t; c != null; c = c.getCause()) {
            if (c instanceof SQLException e && DEADLOCK_DETECTED.equals(e.getSQLState())) {
                return true;
            }
            if (c.getCause() == c) {
                break;
            }
        }
        return false;
    }

    /**
     * The invariants that must hold no matter how threads interleaved or which requests failed:
     * <ul>
     *   <li>no money created or destroyed: the pool's balances still add up to what it was funded with
     *       (it only ever exchanged money internally);</li>
     *   <li>entry is the truth: each cached balance equals the sum of that account's entries.</li>
     * </ul>
     */
    static void assertMoneyConserved(NamedParameterJdbcTemplate jdbc, Pool pool) {
        var params = new MapSqlParameterSource("ids", pool.ids());

        Map<Long, Long> cached = new HashMap<>();
        jdbc.query("SELECT id, balance_minor FROM account WHERE id IN (:ids)", params,
                rs -> { cached.put(rs.getLong(1), rs.getLong(2)); });
        Map<Long, Long> fromEntries = new HashMap<>();
        jdbc.query("SELECT account_id, sum(amount_minor) FROM entry WHERE account_id IN (:ids) GROUP BY account_id", params,
                rs -> { fromEntries.put(rs.getLong(1), rs.getLong(2)); });

        assertThat(cached.values().stream().mapToLong(Long::longValue).sum())
                .as("sum of pool balances").isEqualTo(pool.initialTotal());
        for (long id : pool.ids()) {
            assertThat(cached.get(id)).as("cached balance of account %d vs SUM(entry)", id).isEqualTo(fromEntries.get(id));
        }
    }

    /** Exactly {@code expected} transfers from this run were posted, each with its two entries: nothing partial survived. */
    static void assertPostedExactly(NamedParameterJdbcTemplate jdbc, String keyPrefix, int expected) {
        var params = new MapSqlParameterSource("p", keyPrefix + "%");
        Integer transfers = jdbc.queryForObject("SELECT count(*) FROM transfer WHERE idempotency_key LIKE :p", params, Integer.class);
        Integer entries = jdbc.queryForObject(
                "SELECT count(*) FROM entry e JOIN transfer t ON t.id = e.transfer_id WHERE t.idempotency_key LIKE :p",
                params, Integer.class);
        assertThat(transfers).as("posted transfers").isEqualTo(expected);
        assertThat(entries).as("entries of posted transfers").isEqualTo(expected * 2);
    }
}
