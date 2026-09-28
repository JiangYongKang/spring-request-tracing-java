package com.github.highcumontoa.springrequesttracingjava.tests;

import com.github.highcumontoa.springrequesttracingjava.metrics.RequestMetrics;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Hammers the service with a mix of successful and failing concurrent requests
 * and asserts the metrics stay exact and the in-flight gauge returns to zero.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class MetricsConsistencyIT {

    private static final Logger log = LoggerFactory.getLogger(MetricsConsistencyIT.class);
    private static final int ROUNDS = 5;
    private static final int REQUESTS_PER_ROUND = 40;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Autowired
    RequestMetrics metrics;

    @Test
    void metricsStayExactUnderSustainedConcurrentLoad() throws Exception {
        for (int round = 0; round < ROUNDS; round++) {
            long totalBefore = metrics.getTotal();
            long failedBefore = metrics.getFailed();

            int failures = hammerConcurrently(REQUESTS_PER_ROUND);

            // No unbounded growth: once the round drains, nothing is in flight.
            assertThat(metrics.getInFlight()).isZero();
            assertThat(metrics.getTotal() - totalBefore)
                    .as("total delta in round %s", round)
                    .isEqualTo(REQUESTS_PER_ROUND);
            assertThat(metrics.getFailed() - failedBefore)
                    .as("failed delta in round %s", round)
                    .isEqualTo(failures);
            log.info("round {} done: total={} failed={} inFlight={}",
                    round, metrics.getTotal(), metrics.getFailed(), metrics.getInFlight());
        }

        // The HTTP-facing metrics endpoint agrees with the in-memory counters.
        // Note: the /metrics request itself is in flight while its body is serialized.
        Map body = restTemplate.getForObject("http://localhost:" + port + "/metrics", Map.class);
        log.info("metrics endpoint: {}", body);
        assertThat(((Number) body.get("inFlightRequests")).longValue()).isEqualTo(1);
        assertThat(((Number) body.get("totalRequests")).longValue()).isEqualTo(metrics.getTotal());
        assertThat(((Number) body.get("failedRequests")).longValue()).isEqualTo(metrics.getFailed());
        // After the /metrics response drained, nothing remains in flight.
        assertThat(metrics.getInFlight()).isZero();
    }

    /** Sends {@code count} concurrent requests, every 4th one failing; returns the failure count. */
    private int hammerConcurrently(int count) throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(12);
        List<Future<Boolean>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < count; i++) {
                boolean shouldFail = i % 4 == 0;
                String path = shouldFail ? "/api/fail" : "/api/async";
                futures.add(pool.submit(() -> {
                    try {
                        restTemplate.getForEntity("http://localhost:" + port + path, Map.class);
                    } catch (Exception ignored) {
                        // 500 responses surface as exceptions; they are expected on the failure path.
                    }
                    return shouldFail;
                }));
            }
            int failures = 0;
            for (Future<Boolean> future : futures) {
                if (future.get(60, TimeUnit.SECONDS)) {
                    failures++;
                }
            }
            return failures;
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
    }
}
