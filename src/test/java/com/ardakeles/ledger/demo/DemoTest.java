package com.ardakeles.ledger.demo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.awaitility.Awaitility.await;

import com.ardakeles.ledger.TestcontainersConfig;
import com.ardakeles.ledger.demo.DemoSnapshot.AccountView;
import com.ardakeles.ledger.service.LockingMode;
import com.fasterxml.jackson.databind.JsonNode;
import com.fasterxml.jackson.databind.ObjectMapper;
import java.net.URI;
import java.net.http.HttpClient;
import java.net.http.HttpRequest;
import java.net.http.HttpResponse;
import java.time.Duration;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.Iterator;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;
import java.util.stream.Collectors;
import java.util.stream.Stream;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.api.Timeout;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.context.annotation.Import;
import org.springframework.http.HttpStatus;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.namedparam.MapSqlParameterSource;
import org.springframework.jdbc.core.namedparam.NamedParameterJdbcTemplate;

/**
 * The chaos demo end to end, over real HTTP against real Postgres. The claims the page makes are
 * the claims asserted here: the invariant never moves while balances do, safe mode never deadlocks,
 * unsafe mode does yet stays correct, and the page can really be driven and watched.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT,
        properties = {"ledger.demo.enabled=true", "spring.datasource.hikari.maximum-pool-size=40"})
@Import(TestcontainersConfig.class)
@Timeout(value = 120, unit = TimeUnit.SECONDS)
class DemoTest {

    @Autowired DemoService demo;
    @Autowired LockingMode lockingMode;
    @Autowired TestRestTemplate rest;
    @Autowired NamedParameterJdbcTemplate jdbc;
    @Autowired ObjectMapper mapper;
    @LocalServerPort int port;

    @AfterEach
    void backToSafeAndIdle() {
        lockingMode.setOrdered(true);
        awaitIdle();
    }

    @Test
    void safeRunCompletesEverythingAndTheTotalNeverMovesWhileBalancesDo() {
        ResponseEntity<Map> fired = fire(false);
        assertThat(fired.getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        List<DemoSnapshot> samples = sampleUntilIdle();
        DemoSnapshot end = demo.snapshot();

        assertThat(end.counters().completed()).isEqualTo(500);
        assertThat(end.counters().failed()).isZero();
        assertThat(end.counters().deadlocked()).isZero();
        assertThat(end.history().get(0).mode()).isEqualTo("SAFE");
        assertInvariantHeldInEverySample(samples);
        assertBalancesActuallyMoved(samples);
        assertCacheEqualsEntries(end);
    }

    @Test
    void unsafeRunDeadlocksButNeverBreaksTheInvariantOrLeavesPartialWrites() {
        setMode(false);
        fire(false);

        List<DemoSnapshot> samples = sampleUntilIdle();
        DemoSnapshot end = demo.snapshot();

        DemoSnapshot.Counters c = end.counters();
        assertThat(c.deadlocked()).as("unsafe mode must deadlock").isPositive();
        assertThat(c.completed() + c.failed()).isEqualTo(500);
        assertThat(c.failed()).as("without retry every deadlock is a failed transfer").isEqualTo(c.deadlocked());
        assertThat(end.history().get(0).mode()).isEqualTo("UNSAFE");
        assertInvariantHeldInEverySample(samples);
        assertCacheEqualsEntries(end); // rolled-back victims left nothing behind
    }

    /**
     * Retrying inside unsafe mode is a deadlock storm (victims retry straight back into the tangle), so
     * this run is flipped to safe once retries have been observed, letting the rest drain quickly.
     */
    @Test
    void retryingDeadlockVictimsIsCountedAndStillLeavesTheLedgerCorrect() {
        setMode(false);
        fire(true);
        await().atMost(Duration.ofSeconds(60)).until(() -> demo.snapshot().counters().retried() >= 3);
        setMode(true);

        List<DemoSnapshot> samples = sampleUntilIdle();
        DemoSnapshot.Counters c = demo.snapshot().counters();

        assertThat(c.retried()).isPositive();
        assertThat(c.retried()).as("every retry follows a deadlock").isLessThanOrEqualTo(c.deadlocked());
        assertThat(c.completed() + c.failed()).isEqualTo(500);
        assertInvariantHeldInEverySample(samples);
        assertCacheEqualsEntries(demo.snapshot());
    }

    @Test
    void flippingToSafeMidRunIsAllowedAndLabelledMixed() {
        setMode(false);
        fire(false);
        await().atMost(Duration.ofSeconds(30)).until(() -> demo.snapshot().counters().deadlocked() >= 3);

        setMode(true); // the GIF moment: turn the fix on while the bug is happening

        List<DemoSnapshot> samples = sampleUntilIdle();
        DemoSnapshot end = demo.snapshot();
        assertThat(end.history().get(0).mode()).isEqualTo("MIXED");
        assertThat(end.counters().completed() + end.counters().failed()).isEqualTo(500);
        assertInvariantHeldInEverySample(samples);
    }

    @Test
    void aSecondRunCannotStartWhileOneIsInProgress() {
        setMode(false); // unsafe runs take seconds, so the first is reliably still going
        assertThat(fire(false).getStatusCode()).isEqualTo(HttpStatus.ACCEPTED);

        assertThat(fire(false).getStatusCode()).isEqualTo(HttpStatus.CONFLICT);
    }

    @Test
    void thePageIsServedAndTheStreamDeliversLiveSnapshots() throws Exception {
        ResponseEntity<String> page = rest.getForEntity("/demo", String.class);
        assertThat(page.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(page.getHeaders().getContentType().toString()).startsWith("text/html");
        assertThat(page.getBody()).contains("Ledger chaos demo", "EventSource('/demo/events')");

        HttpClient client = HttpClient.newHttpClient();
        HttpRequest request = HttpRequest.newBuilder(URI.create("http://localhost:" + port + "/demo/events"))
                .header("Accept", "text/event-stream").build();
        ExecutorService reader = Executors.newSingleThreadExecutor();
        try {
            Future<List<JsonNode>> events = reader.submit(() -> {
                HttpResponse<Stream<String>> response = client.send(request, HttpResponse.BodyHandlers.ofLines());
                assertThat(response.headers().firstValue("Content-Type").orElse("")).startsWith("text/event-stream");
                List<JsonNode> seen = new ArrayList<>();
                Iterator<String> lines = response.body().iterator();
                boolean fired = false;
                int firstRun = -1;
                while (lines.hasNext()) {
                    String line = lines.next();
                    if (!line.startsWith("data:")) {
                        continue;
                    }
                    JsonNode snapshot = mapper.readTree(line.substring("data:".length()));
                    seen.add(snapshot);
                    if (!fired) {
                        fired = true; // fire only after the first snapshot arrived, so we watch it change
                        firstRun = snapshot.path("counters").path("run").asInt();
                        fire(false);
                    }
                    // The demo pool is shared across tests, so earlier runs' counters are already non-zero:
                    // wait for a NEWER run to show progress.
                    if (snapshot.path("counters").path("run").asInt() > firstRun
                            && snapshot.path("counters").path("completed").asInt() > 0) {
                        return seen;
                    }
                }
                return seen;
            });
            List<JsonNode> seen = events.get(30, TimeUnit.SECONDS);

            assertThat(seen.size()).as("first paint plus live updates").isGreaterThanOrEqualTo(2);
            JsonNode first = seen.get(0);
            assertThat(first.path("accounts")).hasSize(8);
            assertThat(first.path("poolSumMinor").asLong()).isEqualTo(first.path("expectedPoolSumMinor").asLong());
            assertThat(seen.get(seen.size() - 1).path("counters").path("completed").asInt()).isPositive();
        } finally {
            reader.shutdownNow();
        }
    }

    // ---- helpers ----

    private ResponseEntity<Map> fire(boolean retry) {
        return rest.postForEntity("/demo/fire", Map.of("retryDeadlocks", retry), Map.class);
    }

    private void setMode(boolean safe) {
        assertThat(rest.postForEntity("/demo/mode", Map.of("safe", safe), Map.class).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        assertThat(lockingMode.isOrdered()).isEqualTo(safe);
    }

    private void awaitIdle() {
        await().atMost(Duration.ofSeconds(90)).until(() -> !demo.snapshot().running());
    }

    /** Polls the same snapshot the page receives, as fast as is reasonable, until the run ends. */
    private List<DemoSnapshot> sampleUntilIdle() {
        List<DemoSnapshot> samples = new ArrayList<>();
        long deadline = System.nanoTime() + TimeUnit.SECONDS.toNanos(90);
        DemoSnapshot s;
        do {
            s = demo.snapshot();
            samples.add(s);
            sleep(10);
        } while (s.running() && System.nanoTime() < deadline);
        assertThat(s.running()).as("run finished within the deadline").isFalse();
        return samples;
    }

    /** The headline claim: the displayed total is identical in every snapshot, and the all-accounts total is zero. */
    private static void assertInvariantHeldInEverySample(List<DemoSnapshot> samples) {
        assertThat(samples.stream().filter(DemoSnapshot::running).count())
                .as("snapshots taken while the run was in progress").isGreaterThanOrEqualTo(3);
        for (DemoSnapshot s : samples) {
            assertThat(s.poolSumMinor()).as("pool total in a snapshot").isEqualTo(s.expectedPoolSumMinor());
            assertThat(s.globalSumMinor()).as("sum over all accounts").isZero();
        }
    }

    /** ...and the constant total is not a trivial one: the individual balances really did change underneath it. */
    private static void assertBalancesActuallyMoved(List<DemoSnapshot> samples) {
        Set<Map<Long, Long>> distinct = samples.stream()
                .map(s -> s.accounts().stream().collect(Collectors.toMap(AccountView::id, AccountView::balanceMinor)))
                .collect(Collectors.toSet());
        assertThat(distinct.size()).as("distinct balance vectors observed").isGreaterThan(2);
    }

    /** entry is the source of truth: each pool account's cached balance equals the sum of its entries. */
    private void assertCacheEqualsEntries(DemoSnapshot s) {
        List<Long> ids = s.accounts().stream().map(AccountView::id).toList();
        Map<Long, Long> fromEntries = new HashMap<>();
        jdbc.query("SELECT account_id, sum(amount_minor) FROM entry WHERE account_id IN (:ids) GROUP BY account_id",
                new MapSqlParameterSource("ids", ids), rs -> { fromEntries.put(rs.getLong(1), rs.getLong(2)); });
        for (AccountView a : s.accounts()) {
            assertThat(fromEntries.get(a.id())).as("entries vs cached balance of account %s", a.name())
                    .isEqualTo(a.balanceMinor());
        }
    }

    private static void sleep(long millis) {
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            throw new IllegalStateException(e);
        }
    }
}
