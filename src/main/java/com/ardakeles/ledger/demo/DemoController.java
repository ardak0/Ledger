package com.ardakeles.ledger.demo;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotNull;
import java.util.Map;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.core.io.ClassPathResource;
import org.springframework.core.io.Resource;
import org.springframework.http.CacheControl;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ProblemDetail;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.ExceptionHandler;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.servlet.mvc.method.annotation.SseEmitter;

/**
 * The chaos demo's HTTP surface. This bean does not exist unless {@code ledger.demo.enabled=true}, so
 * without that property /demo, and the switch that makes the ledger deadlock on purpose, are simply 404.
 * The page is served from classpath:demo/ (not static/) precisely so it is not reachable otherwise.
 */
@RestController
@ConditionalOnProperty(prefix = "ledger.demo", name = "enabled", havingValue = "true")
class DemoController {

    private final DemoService demo;
    private final DemoEventStream events;

    DemoController(DemoService demo, DemoEventStream events) {
        this.demo = demo;
        this.events = events;
    }

    record ModeRequest(@NotNull Boolean safe) {
    }

    record FireRequest(Boolean retryDeadlocks) {
    }

    @GetMapping(value = "/demo", produces = MediaType.TEXT_HTML_VALUE)
    ResponseEntity<Resource> page() {
        return ResponseEntity.ok().cacheControl(CacheControl.noStore()).body(new ClassPathResource("demo/index.html"));
    }

    @GetMapping(value = "/demo/events", produces = MediaType.TEXT_EVENT_STREAM_VALUE)
    SseEmitter stream() {
        return events.subscribe();
    }

    @GetMapping("/demo/state")
    DemoSnapshot state() {
        return demo.snapshot();
    }

    /** Switches lock acquisition between ascending-id order (safe) and request order (deadlocks). Allowed mid-run. */
    @PostMapping("/demo/mode")
    Map<String, String> mode(@Valid @RequestBody ModeRequest request) {
        demo.setSafeMode(request.safe());
        return Map.of("mode", request.safe() ? "SAFE" : "UNSAFE");
    }

    @PostMapping("/demo/fire")
    ResponseEntity<Map<String, Integer>> fire(@RequestBody(required = false) FireRequest request) {
        boolean retry = request != null && Boolean.TRUE.equals(request.retryDeadlocks());
        int run = demo.startRun(retry);
        return ResponseEntity.status(HttpStatus.ACCEPTED).body(Map.of("run", run));
    }

    @ExceptionHandler(DemoBusyException.class)
    ProblemDetail busy(DemoBusyException e) {
        ProblemDetail problem = ProblemDetail.forStatusAndDetail(HttpStatus.CONFLICT, e.getMessage());
        problem.setTitle("Run in progress");
        problem.setProperty("code", "RUN_IN_PROGRESS");
        return problem;
    }
}
