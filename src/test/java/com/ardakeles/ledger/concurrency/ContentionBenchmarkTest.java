package com.ardakeles.ledger.concurrency;

import static com.ardakeles.ledger.concurrency.LoadHarness.assertMoneyConserved;
import static com.ardakeles.ledger.concurrency.LoadHarness.assertPostedExactly;
import static com.ardakeles.ledger.concurrency.LoadHarness.createPool;
import static com.ardakeles.ledger.concurrency.LoadHarness.randomWorkload;
import static com.ardakeles.ledger.concurrency.LoadHarness.run;
import static org.assertj.core.api.Assertions.assertThat;

import com.ardakeles.ledger.concurrency.LoadHarness.Pool;
import com.ardakeles.ledger.concurrency.LoadHarness.RunResult;
import com.ardakeles.ledger.domain.TransferRequest;
import com.ardakeles.ledger.repository.LedgerRepository;
import com.ardakeles.ledger.service.OptimisticTransferService;
import com.ardakeles.ledger.service.PessimisticTransferService;
import com.ardakeles.ledger.service.TransferService;
import io.micrometer.core.instrument.MeterRegistry;
import com.github.dockerjava.api.model.Info;
import java.io.IOException;
import java.nio.file.Files;
import java.nio.file.Path;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import org.junit.jupiter.api.Tag;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.context.annotation.Import;
import org.springframework.jdbc.core.JdbcTemplate;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;
import org.testcontainers.DockerClientFactory;

/**
 * Pessimistic (row locks) versus optimistic (version check + retry) under increasing contention, against
 * a durable Postgres. Run with {@code ./mvnw verify -Pbenchmark}; results land in target/benchmark/results.md.
 *
 * <p>Method: identical workload for both strategies (same seed); one discarded warm-up per strategy; 5
 * measured repetitions per cell, alternating which strategy goes first so neither benefits from running
 * second; fresh funded accounts per run; every measured run is verified (money conserved, nothing partial)
 * because a fast wrong answer is worthless. Latency is per successful transfer, retries included.
 * A hand-rolled harness rather than JMH: JMH is built for micro-benchmarks, and this is an end-to-end
 * database workload where the interesting behaviour is contention between threads.
 */
@Tag("benchmark")
@SpringBootTest(properties = "spring.datasource.hikari.maximum-pool-size=20")
@Import(BenchmarkContainerConfig.class)
class ContentionBenchmarkTest {

    private static final Logger log = LoggerFactory.getLogger(ContentionBenchmarkTest.class);

    static final int THREADS = 20;
    static final int REPETITIONS = 5;
    static final long MAX_AMOUNT = 1_000;

    record Scenario(String name, int accounts, int transfers, double hotShare) {
    }

    static final List<Scenario> SCENARIOS = List.of(
            new Scenario("2 accounts (extreme)", 2, 2_000, 0),
            new Scenario("10 accounts (high)", 10, 10_000, 0),
            new Scenario("50 accounts (headline)", 50, 10_000, 0),
            new Scenario("500 accounts (low)", 500, 10_000, 0),
            new Scenario("50 accounts, 50% via one hot account", 50, 10_000, 0.5));

    record Sample(double throughput, double p50Millis, double p99Millis, long conflicts, int succeeded,
                  int abandoned, int requests) {
    }

    @Autowired PessimisticTransferService pessimistic;
    @Autowired OptimisticTransferService optimistic;
    @Autowired LedgerRepository repository;
    @Autowired NamedParameterJdbcTemplate namedJdbc;
    @Autowired JdbcTemplate jdbc;
    @Autowired MeterRegistry meters;

    @Test
    @Timeout(value = 90, unit = TimeUnit.MINUTES)
    void benchmark() throws Exception {
        log.info("warm-up (discarded)");
        Scenario warmup = new Scenario("warm-up", 50, 3_000, 0);
        measure(pessimistic, warmup, 1);
        measure(optimistic, warmup, 1);

        Map<String, Map<String, List<Sample>>> results = new LinkedHashMap<>();
        for (Scenario scenario : SCENARIOS) {
            Map<String, List<Sample>> cell = new LinkedHashMap<>();
            cell.put("pessimistic", new ArrayList<>());
            cell.put("optimistic", new ArrayList<>());
            for (int rep = 0; rep < REPETITIONS; rep++) {
                long seed = 1_000L + rep; // both strategies see the same request pattern in a repetition
                boolean pessimisticFirst = rep % 2 == 0;
                log.info("scenario='{}' rep={} order={}", scenario.name(), rep, pessimisticFirst ? "P,O" : "O,P");
                if (pessimisticFirst) {
                    cell.get("pessimistic").add(measure(pessimistic, scenario, seed));
                    cell.get("optimistic").add(measure(optimistic, scenario, seed));
                } else {
                    cell.get("optimistic").add(measure(optimistic, scenario, seed));
                    cell.get("pessimistic").add(measure(pessimistic, scenario, seed));
                }
            }
            results.put(scenario.name(), cell);
        }

        String report = report(results);
        Path out = Path.of("target", "benchmark", "results.md");
        Files.createDirectories(out.getParent());
        Files.writeString(out, report);
        log.info("benchmark results written to {}\n{}", out, report);
    }

    private Sample measure(TransferService service, Scenario scenario, long seed) throws Exception {
        // Funded so that no transfer can fail for lack of money: this measures concurrency control, not business rejections.
        Pool pool = createPool(repository, pessimistic, scenario.accounts(), (long) scenario.transfers() * MAX_AMOUNT);
        List<TransferRequest> workload = randomWorkload(pool, scenario.transfers(), MAX_AMOUNT, seed, scenario.hotShare());
        String prefix = "bench-" + UUID.randomUUID() + "-";

        double conflictsBefore = conflicts();
        RunResult result = run(service, workload, THREADS, prefix, 600);
        long conflicts = Math.round(conflicts() - conflictsBefore);

        assertThat(result.unexpected()).as("unexpected failures").isEmpty();
        assertThat(result.deadlocks()).as("deadlocks").isZero();
        assertThat(result.insufficientFunds()).isZero();
        assertMoneyConserved(namedJdbc, pool);              // a fast wrong answer is worthless
        assertPostedExactly(namedJdbc, prefix, result.succeeded());

        return new Sample(result.throughputPerSecond(), result.latencyMillis(50), result.latencyMillis(99),
                conflicts, result.succeeded(), result.contention(), scenario.transfers());
    }

    private double conflicts() {
        return meters.get("ledger.transfer.conflicts").counter().count();
    }

    // ---- reporting ----

    private String report(Map<String, Map<String, List<Sample>>> results) {
        StringBuilder md = new StringBuilder();
        md.append("# Contention benchmark: pessimistic vs optimistic\n\n").append(environment()).append("\n");
        md.append("Per cell: median of ").append(REPETITIONS).append(" runs (min-max in brackets). ")
                .append(THREADS).append(" threads, ").append(THREADS).append(" connections. Latency is per successful transfer, retries included.\n\n");
        md.append("| Scenario | Strategy | Throughput (tx/s) | p50 (ms) | p99 (ms) | Conflicts per posted tx | Abandoned |\n");
        md.append("|---|---|---|---|---|---|---|\n");
        for (var entry : results.entrySet()) {
            for (var strategy : entry.getValue().entrySet()) {
                List<Sample> samples = strategy.getValue();
                double[] tps = samples.stream().mapToDouble(Sample::throughput).sorted().toArray();
                long conflicts = samples.stream().mapToLong(Sample::conflicts).sum();
                long posted = samples.stream().mapToLong(Sample::succeeded).sum();
                long abandoned = samples.stream().mapToLong(Sample::abandoned).sum();
                long requests = samples.stream().mapToLong(Sample::requests).sum();
                md.append(String.format("| %s | %s | %,.0f [%,.0f-%,.0f] | %.1f | %.1f | %s | %s |%n",
                        entry.getKey(), strategy.getKey(), median(tps), tps[0], tps[tps.length - 1],
                        median(samples.stream().mapToDouble(Sample::p50Millis).sorted().toArray()),
                        median(samples.stream().mapToDouble(Sample::p99Millis).sorted().toArray()),
                        strategy.getKey().equals("optimistic") ? String.format("%.2f", conflicts / (double) posted) : "n/a",
                        abandoned == 0 ? "0" : String.format("%d of %,d (%.2f%%)", abandoned, requests, 100.0 * abandoned / requests)));
            }
        }
        md.append("\nThroughput ratio, optimistic / pessimistic (median of medians):\n\n");
        for (var entry : results.entrySet()) {
            double p = median(entry.getValue().get("pessimistic").stream().mapToDouble(Sample::throughput).sorted().toArray());
            double o = median(entry.getValue().get("optimistic").stream().mapToDouble(Sample::throughput).sorted().toArray());
            md.append(String.format("- %s: **%.2fx**%n", entry.getKey(), o / p));
        }
        return md.toString();
    }

    private static double median(double[] sorted) {
        int n = sorted.length;
        return n % 2 == 1 ? sorted[n / 2] : (sorted[n / 2 - 1] + sorted[n / 2]) / 2;
    }

    private String environment() {
        StringBuilder env = new StringBuilder("## Environment\n\n");
        env.append("- Date: ").append(LocalDate.now()).append('\n');
        env.append("- Java: ").append(System.getProperty("java.version")).append(", OS: ").append(System.getProperty("os.name"))
                .append(' ').append(System.getProperty("os.version")).append(", ").append(Runtime.getRuntime().availableProcessors())
                .append(" logical cores");
        String cpu = shell("sysctl", "-n", "machdep.cpu.brand_string");
        env.append(cpu.isBlank() ? "" : " (" + cpu + ")").append('\n');
        try {
            Info docker = DockerClientFactory.instance().client().infoCmd().exec();
            env.append("- Docker: ").append(docker.getServerVersion()).append(", VM with ").append(docker.getNCPU())
                    .append(" CPUs and ").append(docker.getMemTotal() / (1024 * 1024)).append(" MiB\n");
        } catch (RuntimeException e) {
            env.append("- Docker: (info unavailable)\n");
        }
        env.append("- Postgres: ").append(jdbc.queryForObject("SELECT version()", String.class).split(" on ")[0])
                .append(" in a container; fsync=").append(jdbc.queryForObject("SHOW fsync", String.class))
                .append(", synchronous_commit=").append(jdbc.queryForObject("SHOW synchronous_commit", String.class))
                .append(", shared_buffers=").append(jdbc.queryForObject("SHOW shared_buffers", String.class)).append('\n');
        env.append("- Optimistic retry budget: ").append("20 attempts, full-jitter backoff 0.2ms doubling to a 20ms cap\n");
        return env.toString();
    }

    private static String shell(String... command) {
        try {
            Process p = new ProcessBuilder(command).redirectErrorStream(true).start();
            String out = new String(p.getInputStream().readAllBytes()).trim();
            return p.waitFor() == 0 ? out : "";
        } catch (IOException | InterruptedException e) {
            return "";
        }
    }
}
