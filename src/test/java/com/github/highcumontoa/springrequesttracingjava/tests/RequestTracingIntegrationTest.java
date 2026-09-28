package com.github.highcumontoa.springrequesttracingjava.tests;

import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.client.TestRestTemplate;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.ResponseEntity;

import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.atomic.AtomicInteger;
import java.util.stream.IntStream;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * End-to-end tests against the real servlet container: trace id generation,
 * propagation across sync / async / outbound / error paths, concurrency
 * isolation and metric consistency.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class RequestTracingIntegrationTest {

    private static final Logger log = LoggerFactory.getLogger(RequestTracingIntegrationTest.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate rest;

    private String url(String path) {
        return "http://localhost:" + port + path;
    }

    @SuppressWarnings("unchecked")
    private Map<String, Object> body(ResponseEntity<String> response) {
        // The demo endpoints return simple JSON; avoid a hard Jackson dependency dance.
        try {
            return new com.fasterxml.jackson.databind.ObjectMapper()
                    .readValue(response.getBody(), Map.class);
        } catch (com.fasterxml.jackson.core.JsonProcessingException e) {
            throw new IllegalStateException(e);
        }
    }

    @Test
    void syncRequestGetsTraceIdAndItIsQueryable() {
        ResponseEntity<String> response = rest.getForEntity(url("/demo/sync"), String.class);

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).hasSize(32);
        assertThat(body(response).get("traceId")).isEqualTo(traceId);

        ResponseEntity<String> trace = rest.getForEntity(
                url("/observability/traces/" + traceId), String.class);
        assertThat(trace.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(trace.getBody())
                .contains("request-start")
                .contains("controller-sync")
                .contains("request-complete");
    }

    @Test
    void inboundTraceIdIsHonoured() {
        HttpHeaders headers = new HttpHeaders();
        headers.set("X-Trace-Id", "caller-supplied-id-1234");
        ResponseEntity<String> response = rest.exchange(url("/demo/sync"),
                HttpMethod.GET, new HttpEntity<>(headers), String.class);

        assertThat(response.getHeaders().getFirst("X-Trace-Id"))
                .isEqualTo("caller-supplied-id-1234");
        assertThat(body(response).get("traceId")).isEqualTo("caller-supplied-id-1234");
    }

    @Test
    void asyncRequestKeepsSameTraceIdOnWorkerThread() {
        ResponseEntity<String> response = rest.getForEntity(url("/demo/async"), String.class);

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).hasSize(32);
        Map<String, Object> json = body(response);
        assertThat(json.get("traceId")).isEqualTo(traceId);
        // The worker thread embedded the same id in its result.
        assertThat(String.valueOf(json.get("result"))).endsWith(traceId);

        ResponseEntity<String> trace = rest.getForEntity(
                url("/observability/traces/" + traceId), String.class);
        assertThat(trace.getBody())
                .contains("async-started")
                .contains("async-step-begin")
                .contains("async-step-end");
    }

    @Test
    void errorPathKeepsTraceIdAndCountsFailure() {
        long failedBefore = metrics().failed();

        ResponseEntity<String> response = rest.getForEntity(url("/demo/error"), String.class);
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();

        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).hasSize(32);

        ResponseEntity<String> trace = rest.getForEntity(
                url("/observability/traces/" + traceId), String.class);
        assertThat(trace.getBody())
                .contains("controller-error-throw")
                .contains("controller-error")
                .contains("request-failed");

        assertThat(metrics().failed()).isEqualTo(failedBefore + 1);
    }

    @Test
    void asyncFailureKeepsTraceIdAndCountsFailure() {
        long failedBefore = metrics().failed();

        ResponseEntity<String> response =
                rest.getForEntity(url("/demo/async-error"), String.class);
        assertThat(response.getStatusCode().is5xxServerError()).isTrue();
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).hasSize(32);

        ResponseEntity<String> trace = rest.getForEntity(
                url("/observability/traces/" + traceId), String.class);
        assertThat(trace.getBody())
                .contains("async-failing-begin")
                .contains("async-failing-end")
                .contains("request-failed");

        assertThat(metrics().failed()).isEqualTo(failedBefore + 1);
        assertThat(metrics().inFlight()).isZero();
    }

    @Test
    void asyncTimeoutRecordsTimeoutNodeAndStillCompletesTrace() {
        long failedBefore = metrics().failed();

        // The worker sleeps 5s; the container async timeout is configured to 2s.
        ResponseEntity<String> response =
                rest.getForEntity(url("/demo/async-timeout?millis=5000"), String.class);
        assertThat(response.getStatusCode().isError()).isTrue();
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).hasSize(32);

        // Give the async-completion callback a moment to settle after the timeout.
        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
                .until(() -> {
                    String body = rest.getForEntity(
                            url("/observability/traces/" + traceId), String.class).getBody();
                    return body != null && body.contains("async-timeout")
                            && body.contains("request-failed");
                });

        org.awaitility.Awaitility.await().atMost(java.time.Duration.ofSeconds(3))
                .until(() -> metrics().failed() == failedBefore + 1);
        assertThat(metrics().inFlight()).isZero();
    }

    @Test
    void outboundCallPropagatesTraceIdToNestedRequest() {
        ResponseEntity<String> response = rest.getForEntity(url("/demo/outbound"), String.class);
        String traceId = response.getHeaders().getFirst("X-Trace-Id");
        assertThat(traceId).hasSize(32);

        Map<String, Object> json = body(response);
        assertThat(json.get("traceId")).isEqualTo(traceId);
        // The nested /demo/sync call saw the same id via the outbound header.
        assertThat(String.valueOf(json.get("upstream"))).contains(traceId);

        ResponseEntity<String> trace = rest.getForEntity(
                url("/observability/traces/" + traceId), String.class);
        assertThat(trace.getBody())
                .contains("outbound-start")
                .contains("outbound-end")
                .contains("controller-outbound");
    }

    @Test
    void concurrentRequestsDoNotCrossContaminateAndCountersAreConsistent() throws Exception {
        int concurrency = 40;
        int iterations = 25; // 40 * 25 = 1000 mixed requests
        ExecutorService pool = Executors.newFixedThreadPool(concurrency);
        ConcurrentHashMap<String, Boolean> seenIds = new ConcurrentHashMap<>();
        AtomicInteger mismatches = new AtomicInteger();
        MetricsSnapshot before = metrics();
        try {
            for (int round = 0; round < iterations; round++) {
                java.util.List<Future<?>> futures = IntStream.range(0, concurrency)
                        .<Future<?>>mapToObj(i -> pool.submit(() -> {
                            String path = switch (i % 5) {
                                case 0 -> "/demo/sync";
                                case 1 -> "/demo/async";
                                case 2 -> "/demo/error";
                                case 3 -> "/demo/outbound";
                                default -> "/demo/async-error";
                            };
                            boolean failingPath =
                                    path.equals("/demo/error") || path.equals("/demo/async-error");
                            try {
                                ResponseEntity<String> response =
                                        rest.getForEntity(url(path), String.class);
                                String id = response.getHeaders().getFirst("X-Trace-Id");
                                if (id == null || id.length() != 32) {
                                    log.warn("MISMATCH bad header path={} status={}",
                                            path, response.getStatusCode());
                                    mismatches.incrementAndGet();
                                    return;
                                }
                                if (seenIds.putIfAbsent(id, Boolean.TRUE) != null) {
                                    log.warn("MISMATCH duplicated id path={} id={}", path, id);
                                    mismatches.incrementAndGet(); // id reused across requests
                                    return;
                                }
                                // Successful bodies must echo the same header id.
                                // The outbound path makes a nested call with the
                                // same id, so it is expected (and allowed) to see
                                // that id repeated within the body.
                                if (!failingPath && !response.getBody().contains(id)) {
                                    log.warn("MISMATCH body id differs path={} id={} body={}",
                                            path, id, response.getBody());
                                    mismatches.incrementAndGet();
                                }
                            } catch (Exception e) {
                                log.warn("MISMATCH request threw path={}", path, e);
                                mismatches.incrementAndGet();
                            }
                        }))
                        .toList();
                for (Future<?> f : futures) {
                    f.get();
                }
            }
        } finally {
            pool.shutdown();
        }

        log.info("concurrent run: unique trace ids = {}, mismatches = {}",
                seenIds.size(), mismatches.get());
        assertThat(mismatches).hasValue(0);
        // Every outer request has a unique id; the nested call made by
        // /demo/outbound carries the SAME id (it never produces a new one).
        assertThat(seenIds).hasSize(concurrency * iterations);

        MetricsSnapshot after = metrics();
        // Every request finished (synchronously or via async completion).
        assertThat(after.inFlight()).isZero();
        long outerRequests = (long) concurrency * iterations;
        // 1/5 of outer requests is /demo/outbound, each adding one nested call.
        assertThat(after.total() - before.total())
                .isEqualTo(outerRequests + outerRequests / 5);
        // 2/5 of the outer load fails (/demo/error and /demo/async-error);
        // nested calls all succeed and must not be counted as failures.
        assertThat(after.failed() - before.failed())
                .isEqualTo(outerRequests * 2 / 5);

        // Bounded archive: 800+ requests but only capacity-sized retention.
        ResponseEntity<String[]> traces =
                rest.getForEntity(url("/observability/traces"), String[].class);
        assertThat(traces.getBody()).hasSizeLessThanOrEqualTo(200);
    }

    private record MetricsSnapshot(long total, long failed, int inFlight) {
    }

    private MetricsSnapshot metrics() {
        @SuppressWarnings("unchecked")
        Map<String, Object> json = rest.getForEntity(
                url("/observability/metrics"), Map.class).getBody();
        return new MetricsSnapshot(
                ((Number) json.get("totalRequests")).longValue(),
                ((Number) json.get("failedRequests")).longValue(),
                ((Number) json.get("inFlight")).intValue());
    }
}
