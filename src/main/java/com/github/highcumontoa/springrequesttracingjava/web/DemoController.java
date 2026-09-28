package com.github.highcumontoa.springrequesttracingjava.web;

import com.github.highcumontoa.springrequesttracingjava.async.DemoAsyncService;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestClient;

import java.util.Map;
import java.util.concurrent.CompletableFuture;

/** Demo endpoints exercising the sync, async, error and outbound paths. */
@RestController
@RequestMapping("/demo")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final DemoAsyncService asyncService;
    private final RestClient restClient;
    private final LocalPortHolder portHolder;

    public DemoController(DemoAsyncService asyncService, RestClient.Builder restClientBuilder,
                          LocalPortHolder portHolder) {
        this.asyncService = asyncService;
        this.restClient = restClientBuilder.build();
        this.portHolder = portHolder;
    }

    /** Plain synchronous request: logs and checkpoints carry the trace id. */
    @RequestMapping("/sync")
    public Map<String, Object> sync() {
        String traceId = TraceContext.currentTraceId();
        log.info("sync handler traceId={}", traceId);
        TraceContext.event("controller-sync", null);
        return Map.of("traceId", traceId, "node", "sync");
    }

    /**
     * Asynchronous request: the controller thread returns immediately while the
     * worker thread (same trace id) finishes the work. Spring MVC keeps the
     * request open until the future completes.
     */
    @RequestMapping("/async")
    public CompletableFuture<Map<String, Object>> async() {
        String traceId = TraceContext.currentTraceId();
        log.info("async handler dispatch traceId={}", traceId);
        TraceContext.event("controller-async-dispatch", null);
        return asyncService.step("demo")
                .thenApply(result -> Map.<String, Object>of("traceId", traceId, "result", result));
    }

    /** Async request whose worker task completes exceptionally. */
    @RequestMapping("/async-error")
    public CompletableFuture<Map<String, Object>> asyncError() {
        String traceId = TraceContext.currentTraceId();
        log.info("async-error handler dispatch traceId={}", traceId);
        TraceContext.event("controller-async-error-dispatch", null);
        return asyncService.failingStep("demo")
                .thenApply(result -> Map.<String, Object>of("traceId", traceId, "result", result));
    }

    /** Async request whose worker outlives {@code spring.mvc.async.request-timeout}. */
    @RequestMapping("/async-timeout")
    public CompletableFuture<Map<String, Object>> asyncTimeout(
            @RequestParam(defaultValue = "5000") long millis) {
        String traceId = TraceContext.currentTraceId();
        log.info("async-timeout handler dispatch traceId={} millis={}", traceId, millis);
        TraceContext.event("controller-async-timeout-dispatch", "millis=" + millis);
        return asyncService.slowStep("slow", millis)
                .thenApply(result -> Map.<String, Object>of("traceId", traceId, "result", result));
    }

    /** Always throws; the advice and filter must retain the trace id. */
    @RequestMapping("/error")
    public Map<String, Object> error() {
        String traceId = TraceContext.currentTraceId();
        log.info("error handler about to throw traceId={}", traceId);
        TraceContext.event("controller-error-throw", null);
        throw new IllegalStateException("boom for trace " + traceId);
    }

    /**
     * Makes an outbound HTTP call. With no {@code url} parameter it calls this
     * same application's {@code /demo/sync} endpoint, so you can observe the
     * trace id being propagated to a nested request.
     */
    @RequestMapping("/outbound")
    public Map<String, Object> outbound(@RequestParam(required = false) String url) {
        String traceId = TraceContext.currentTraceId();
        String target = url != null ? url
                : "http://localhost:" + portHolder.getPort() + "/demo/sync";
        log.info("outbound handler traceId={} target={}", traceId, target);
        TraceContext.event("controller-outbound", target);
        String body = restClient.get().uri(target).retrieve().body(String.class);
        return Map.of("traceId", traceId, "upstream", body);
    }
}
