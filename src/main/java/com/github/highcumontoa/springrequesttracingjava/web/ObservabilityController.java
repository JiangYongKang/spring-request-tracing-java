package com.github.highcumontoa.springrequesttracingjava.web;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceRegistry;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceState;
import org.springframework.http.ResponseEntity;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Human/query-facing view over {@link TraceRegistry}.
 *
 * <p>Standard health and Micrometer metrics remain available via Actuator at
 * {@code /actuator/health} and {@code /actuator/metrics}; these endpoints add
 * trace-id lookup of the key nodes a request passed through.
 */
@RestController
@RequestMapping("/observability")
public class ObservabilityController {

    private final TraceRegistry registry;

    public ObservabilityController(TraceRegistry registry) {
        this.registry = registry;
    }

    /** Cumulative totals plus the current in-flight count. */
    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalRequests", registry.totalRequests());
        body.put("failedRequests", registry.failedRequests());
        // Exclude this very query request, which is still running.
        body.put("inFlight", registry.inFlightExcluding(TraceContext.currentRequestId()));
        return body;
    }

    /** Ids of the most recently finished traces (bounded by the registry). */
    @GetMapping("/traces")
    public List<String> traces() {
        return registry.recent().stream().map(TraceState::traceId).toList();
    }

    /** Every checkpoint recorded for one trace id; 404 when unknown/evicted. */
    @GetMapping("/traces/{traceId}")
    public ResponseEntity<TraceState> trace(@PathVariable String traceId) {
        TraceState state = registry.get(traceId);
        return state == null ? ResponseEntity.notFound().build() : ResponseEntity.ok(state);
    }
}
