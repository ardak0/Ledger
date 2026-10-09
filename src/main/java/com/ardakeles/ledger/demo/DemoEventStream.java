package com.ardakeles.ledger.demo;

import com.fasterxml.jackson.databind.ObjectMapper;
import jakarta.annotation.PreDestroy;
import java.io.IOException;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.Executors;
import java.util.concurrent.ScheduledExecutorService;
import java.util.concurrent.TimeUnit;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.stereotype.Component;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * Server-Sent Events fan-out. One ticker samples the database ten times a second and pushes the same
 * snapshot to every connected page. It samples (rather than pushing one event per transfer) because
 * the invariant number must come from a single SQL statement; summing a stream of per-transfer events
 * in the browser would flicker. The sampling read is a plain SELECT, so deadlocking writers never block it.
 */
@Component
@ConditionalOnProperty(prefix = "ledger.demo", name = "enabled", havingValue = "true")
class DemoEventStream {

    private static final Logger log = LoggerFactory.getLogger(DemoEventStream.class);
    private static final long TICK_MILLIS = 100;
    private static final long KEEPALIVE_NANOS = TimeUnit.SECONDS.toNanos(1);
    private static final long EMITTER_TIMEOUT_MILLIS = TimeUnit.MINUTES.toMillis(30);

    private final DemoService demo;
    private final ObjectMapper mapper;
    private final List<SseEmitter> emitters = new CopyOnWriteArrayList<>();
    private final ScheduledExecutorService ticker = Executors.newSingleThreadScheduledExecutor(
            r -> Thread.ofPlatform().name("demo-sse-ticker").daemon().unstarted(r));

    private String lastJson;
    private long lastSentNanos;

    DemoEventStream(DemoService demo, ObjectMapper mapper) {
        this.demo = demo;
        this.mapper = mapper;
        ticker.scheduleWithFixedDelay(this::tick, TICK_MILLIS, TICK_MILLIS, TimeUnit.MILLISECONDS);
    }

    SseEmitter subscribe() {
        SseEmitter emitter = new SseEmitter(EMITTER_TIMEOUT_MILLIS);
        emitters.add(emitter);
        emitter.onCompletion(() -> emitters.remove(emitter));
        emitter.onTimeout(() -> emitters.remove(emitter));
        emitter.onError(e -> emitters.remove(emitter));
        try {
            emitter.send(SseEmitter.event().name("state").data(json()));  // first paint without waiting for a tick
        } catch (IOException | RuntimeException e) {
            emitters.remove(emitter);
        }
        return emitter;
    }

    private String json() {
        try {
            return mapper.writeValueAsString(demo.snapshot());
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
    }

    private void tick() {
        // An exception escaping a scheduled task silently cancels all future runs, so catch everything.
        try {
            if (emitters.isEmpty()) {
                return;
            }
            String json = json();
            long now = System.nanoTime();
            if (json.equals(lastJson) && now - lastSentNanos < KEEPALIVE_NANOS) {
                return; // nothing changed; still send once a second so the page knows the stream is alive
            }
            lastJson = json;
            lastSentNanos = now;
            for (SseEmitter emitter : emitters) {
                try {
                    emitter.send(SseEmitter.event().name("state").data(json));
                } catch (IOException | IllegalStateException e) {
                    emitters.remove(emitter); // client went away
                }
            }
        } catch (Throwable t) {
            log.warn("demo SSE tick failed", t);
        }
    }

    @PreDestroy
    void shutdown() {
        ticker.shutdownNow();
        emitters.forEach(SseEmitter::complete);
    }
}
