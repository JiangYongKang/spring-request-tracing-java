package com.github.highcumontoa.springrequesttracingjava.web;

import com.github.highcumontoa.springrequesttracingjava.metrics.RequestMetrics;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RestController;

import java.util.LinkedHashMap;
import java.util.Map;

/**
 * Exposes health and request metrics for external probing.
 */
@RestController
public class HealthMetricsController {

    private final RequestMetrics metrics;

    public HealthMetricsController(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @GetMapping("/health")
    public Map<String, Object> health() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("status", "UP");
        body.put("inFlight", metrics.getInFlight());
        return body;
    }

    @GetMapping("/metrics")
    public Map<String, Object> metrics() {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("totalRequests", metrics.getTotal());
        body.put("failedRequests", metrics.getFailed());
        body.put("inFlightRequests", metrics.getInFlight());
        return body;
    }
}
