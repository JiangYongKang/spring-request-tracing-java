package com.github.highcumontoa.springrequesttracingjava.web;

import com.github.highcumontoa.springrequesttracingjava.service.AsyncService;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.client.RestTemplate;

import java.util.LinkedHashMap;
import java.util.Map;
import java.util.concurrent.CompletableFuture;

/**
 * Demo endpoints exercising the sync, async, outbound and failure paths of the
 * tracing pipeline. Every response echoes the trace id observed at each node so
 * callers (and tests) can verify the id stays stable end to end.
 */
@RestController
@RequestMapping("/api")
public class DemoController {

    private static final Logger log = LoggerFactory.getLogger(DemoController.class);

    private final AsyncService asyncService;
    private final RestTemplate restTemplate;

    public DemoController(AsyncService asyncService, RestTemplate restTemplate) {
        this.asyncService = asyncService;
        this.restTemplate = restTemplate;
    }

    /** Pure synchronous path. */
    @GetMapping("/sync")
    public Map<String, Object> sync() {
        log.info("sync node reached");
        return Map.of("node", "sync", "traceId", currentTraceId());
    }

    /** Sync -> async hop; both nodes must report the same trace id. */
    @GetMapping("/async")
    public Map<String, Object> async() throws Exception {
        String syncTraceId = currentTraceId();
        log.info("async endpoint entered");
        CompletableFuture<String> workerTraceId = asyncService.traceIdOnWorkerThread();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", "async");
        body.put("traceId", syncTraceId);
        body.put("asyncTraceId", workerTraceId.get());
        return body;
    }

    /** Async failure path: exception is caught inside the async task, id preserved. */
    @GetMapping("/async-failure")
    public Map<String, Object> asyncFailure() throws Exception {
        String syncTraceId = currentTraceId();
        String workerTraceId = asyncService.traceIdAfterCaughtFailure().get();
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", "async-failure");
        body.put("traceId", syncTraceId);
        body.put("asyncTraceId", workerTraceId);
        return body;
    }

    /** Outbound call: the trace id is forwarded via the X-Trace-Id header. */
    @GetMapping("/outbound")
    public Map<String, Object> outbound(@RequestParam String url) {
        log.info("outbound endpoint entered target={}", url);
        ResponseEntity<String> response = restTemplate.getForEntity(url, String.class);
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("node", "outbound");
        body.put("traceId", currentTraceId());
        body.put("downstreamStatus", response.getStatusCode().value());
        body.put("downstreamTraceId", response.getHeaders().getFirst(TraceContext.TRACE_ID_HEADER));
        return body;
    }

    /** Failure path: throws so the error handling chain runs under the trace id. */
    @GetMapping("/fail")
    public Map<String, Object> fail() {
        log.error("fail endpoint about to throw");
        throw new IllegalStateException("simulated request failure");
    }

    private String currentTraceId() {
        String traceId = TraceContext.currentTraceId();
        return traceId != null ? traceId : "none";
    }
}
