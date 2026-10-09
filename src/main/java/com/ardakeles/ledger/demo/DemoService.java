package com.ardakeles.ledger.demo;

import com.ardakeles.ledger.demo.DemoSnapshot.AccountView;
import com.ardakeles.ledger.demo.DemoSnapshot.Counters;
import com.ardakeles.ledger.demo.DemoSnapshot.RunSummary;
import com.ardakeles.ledger.domain.Account;
import com.ardakeles.ledger.domain.BalanceSnapshot;
import com.ardakeles.ledger.domain.Currency;
import com.ardakeles.ledger.domain.InsufficientFundsException;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.Deadlocks;
import com.ardakeles.ledger.service.LockingMode;
import com.ardakeles.ledger.service.PessimisticTransferService;
import java.util.ArrayDeque;
import java.util.ArrayList;
import java.util.Deque;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.ThreadFactory;
import java.util.concurrent.ThreadLocalRandom;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicBoolean;
import java.util.concurrent.atomic.AtomicInteger;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.ApplicationArguments;
import org.springframework.boot.ApplicationRunner;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Service;

/**
 * Drives the chaos demo: owns a small funded pool of accounts, fires bursts of random transfers
 * through the real {@link TransferService}, counts the outcomes, and produces snapshots for the page.
 * Exists only when {@code ledger.demo.enabled=true}.
 */
@Service
@ConditionalOnProperty(prefix = "ledger.demo", name = "enabled", havingValue = "true")
public class DemoService implements ApplicationRunner {

    private static final Logger log = LoggerFactory.getLogger(DemoService.class);

    static final int ACCOUNTS = 8;
    static final int TRANSFERS_PER_RUN = 500;
    /** Worker threads must stay below the connection pool size (40): the snapshot ticker needs a connection too. */
    static final int WORKERS = 32;
    static final int MAX_RETRIES = 3;
    static final long INITIAL_BALANCE_MINOR = 1_000_000; // 10,000.00
    /** Small relative to balances, so insufficient funds essentially never happens: the demo is about deadlocks. */
    static final long MAX_AMOUNT_MINOR = 1_000;
    static final int HISTORY_SIZE = 5;

    private record Pool(List<Account> accounts, long expectedSumMinor) {
        List<Long> ids() {
            return accounts.stream().map(Account::id).toList();
        }
    }

    /** Mutable state of one run. Counters are atomic: 32 worker threads update them concurrently. */
    private static final class Run {
        final int number;
        final boolean retry;
        final long startNanos = System.nanoTime();
        final AtomicInteger completed = new AtomicInteger();
        final AtomicInteger failed = new AtomicInteger();
        final AtomicInteger deadlocked = new AtomicInteger();
        final AtomicInteger retried = new AtomicInteger();
        final AtomicInteger insufficient = new AtomicInteger();
        final AtomicBoolean sawSafe = new AtomicBoolean();
        final AtomicBoolean sawUnsafe = new AtomicBoolean();
        volatile boolean running = true;
        volatile long finalElapsedMillis;

        Run(int number, boolean retry) {
            this.number = number;
            this.retry = retry;
        }

        long elapsedMillis() {
            return running ? TimeUnit.NANOSECONDS.toMillis(System.nanoTime() - startNanos) : finalElapsedMillis;
        }

        String modeLabel() {
            return sawSafe.get() && sawUnsafe.get() ? "MIXED" : sawUnsafe.get() ? "UNSAFE" : "SAFE";
        }

        Counters counters() {
            return new Counters(number, TRANSFERS_PER_RUN, completed.get(), failed.get(), deadlocked.get(),
                    retried.get(), insufficient.get(), retry, elapsedMillis());
        }
    }

    /** Pinned to the pessimistic implementation: the demo is about lock ordering, whichever strategy the app defaults to. */
    private final PessimisticTransferService transfers;
    private final LedgerRepository repository;
    private final LockingMode lockingMode;
    /** Distinguishes this process's idempotency keys from those of earlier runs against the same database. */
    private final String keyPrefix = "demo-" + UUID.randomUUID().toString().substring(0, 8) + "-";

    private volatile Pool pool;
    private volatile Run current;
    private int runCounter;
    private final Deque<RunSummary> history = new ArrayDeque<>();

    public DemoService(PessimisticTransferService transfers, LedgerRepository repository, LockingMode lockingMode) {
        this.transfers = transfers;
        this.repository = repository;
        this.lockingMode = lockingMode;
    }

    /**
     * Creates the demo's own system funding account and pool, and funds the pool by ordinary
     * transfers: money enters only through the ledger, never by editing a balance. A fresh pool is
     * created at every start. The pool always starts in safe mode.
     */
    @Override
    public void run(ApplicationArguments args) {
        Account funding = repository.createSystemAccount(Currency.USD);
        List<Account> accounts = new ArrayList<>();
        for (int i = 0; i < ACCOUNTS; i++) {
            Account account = repository.createAccount(Currency.USD);
            transfers.execute(keyPrefix + "fund-" + i,
                    new TransferRequest(funding.id(), account.id(), INITIAL_BALANCE_MINOR, Currency.USD));
            accounts.add(account);
        }
        pool = new Pool(List.copyOf(accounts), INITIAL_BALANCE_MINOR * ACCOUNTS);
        log.info("Demo ready: {} accounts of {} minor units each, funded from system account {}",
                ACCOUNTS, INITIAL_BALANCE_MINOR, funding.id());
    }

    public void setSafeMode(boolean safe) {
        lockingMode.setOrdered(safe);
    }

    /** Starts a burst of {@value #TRANSFERS_PER_RUN} random transfers in the background; returns the run number. */
    public synchronized int startRun(boolean retryDeadlocks) {
        Pool p = pool;
        if (p == null) {
            throw new IllegalStateException("demo pool is not ready yet");
        }
        Run existing = current;
        if (existing != null && existing.running) {
            throw new DemoBusyException();
        }
        Run run = new Run(++runCounter, retryDeadlocks);
        current = run;

        List<TransferRequest> workload = randomWorkload(p);
        Thread.ofPlatform().name("demo-run-" + run.number).daemon().start(() -> execute(run, workload));
        return run.number;
    }

    private List<TransferRequest> randomWorkload(Pool p) {
        ThreadLocalRandom random = ThreadLocalRandom.current();
        List<TransferRequest> requests = new ArrayList<>(TRANSFERS_PER_RUN);
        for (int i = 0; i < TRANSFERS_PER_RUN; i++) {
            int from = random.nextInt(ACCOUNTS);
            int to = random.nextInt(ACCOUNTS - 1);
            if (to >= from) {
                to++; // uniform over the other accounts, never the same one
            }
            long amount = 1 + random.nextLong(MAX_AMOUNT_MINOR);
            requests.add(new TransferRequest(p.accounts().get(from).id(), p.accounts().get(to).id(), amount, Currency.USD));
        }
        return requests;
    }

    private void execute(Run run, List<TransferRequest> workload) {
        ExecutorService executor = Executors.newFixedThreadPool(WORKERS, daemonThreads("demo-worker"));
        try {
            for (int i = 0; i < workload.size(); i++) {
                int index = i;
                executor.submit(() -> attempt(run, index, workload.get(index)));
            }
            executor.shutdown();
            executor.awaitTermination(10, TimeUnit.MINUTES);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
        } finally {
            executor.shutdownNow();
            run.finalElapsedMillis = run.elapsedMillis();
            run.running = false;
            synchronized (history) {
                history.addFirst(new RunSummary(run.number, run.modeLabel(), run.retry, TRANSFERS_PER_RUN,
                        run.completed.get(), run.failed.get(), run.deadlocked.get(), run.retried.get(), run.finalElapsedMillis));
                while (history.size() > HISTORY_SIZE) {
                    history.removeLast();
                }
            }
            log.info("Demo run {} finished: completed={} failed={} deadlocked={} retried={} in {}ms",
                    run.number, run.completed, run.failed, run.deadlocked, run.retried, run.finalElapsedMillis);
        }
    }

    /**
     * One transfer, with optional retry of deadlock victims. The retry loop is OUTSIDE the transaction
     * (each call to execute is its own transaction): a transaction Postgres killed is aborted and
     * cannot be continued. Retrying with the SAME idempotency key is safe because the victim's
     * rollback released its claim on the key.
     */
    private void attempt(Run run, int index, TransferRequest request) {
        String key = keyPrefix + "r" + run.number + "-" + index;
        int retries = 0;
        while (true) {
            (lockingMode.isOrdered() ? run.sawSafe : run.sawUnsafe).set(true);
            try {
                transfers.execute(key, request);
                run.completed.incrementAndGet();
                return;
            } catch (InsufficientFundsException e) {
                run.insufficient.incrementAndGet();
                run.failed.incrementAndGet();
                return;
            } catch (Throwable t) {
                if (!Deadlocks.isDeadlock(t)) {
                    log.error("Demo transfer {} failed unexpectedly", key, t);
                    run.failed.incrementAndGet();
                    return;
                }
                run.deadlocked.incrementAndGet();
                if (!run.retry || retries >= MAX_RETRIES) {
                    run.failed.incrementAndGet();
                    return;
                }
                retries++;
                run.retried.incrementAndGet();
                try {
                    Thread.sleep(ThreadLocalRandom.current().nextInt(1, 15)); // jitter, so victims do not collide again in lockstep
                } catch (InterruptedException e) {
                    Thread.currentThread().interrupt();
                    run.failed.incrementAndGet();
                    return;
                }
            }
        }
    }

    public DemoSnapshot snapshot() {
        Pool p = pool;
        String mode = lockingMode.isOrdered() ? "SAFE" : "UNSAFE";
        Run run = current;
        List<RunSummary> recent;
        synchronized (history) {
            recent = List.copyOf(history);
        }
        if (p == null) {
            return new DemoSnapshot(false, mode, false, WORKERS, List.of(), 0, 0, 0, Counters.none(), recent);
        }
        BalanceSnapshot balances = repository.snapshotBalances(p.ids());
        List<AccountView> views = new ArrayList<>();
        long poolSum = 0;
        for (int i = 0; i < p.accounts().size(); i++) {
            long id = p.accounts().get(i).id();
            long balance = balances.balances().get(id);
            poolSum += balance;
            views.add(new AccountView(id, String.valueOf((char) ('A' + i)), balance));
        }
        return new DemoSnapshot(true, mode, run != null && run.running, WORKERS, views, poolSum, p.expectedSumMinor(),
                balances.globalSumMinor(), run == null ? Counters.none() : run.counters(), recent);
    }

    private static ThreadFactory daemonThreads(String prefix) {
        AtomicInteger n = new AtomicInteger();
        return r -> Thread.ofPlatform().name(prefix + "-" + n.incrementAndGet()).daemon().unstarted(r);
    }
}
