package com.github.highcumontoa.springrequesttracingjava.tests;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
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

import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Fires many concurrent requests and asserts each one keeps its own trace id
 * across the sync and async nodes without any cross-contamination.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class ConcurrentTracingIT {

    private static final Logger log = LoggerFactory.getLogger(ConcurrentTracingIT.class);
    private static final int REQUESTS = 64;

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void concurrentRequestsNeverShareOrLoseTraceIds() throws Exception {
        ExecutorService pool = Executors.newFixedThreadPool(16);
        CountDownLatch go = new CountDownLatch(1);
        Set<String> seenTraceIds = ConcurrentHashMap.newKeySet();
        List<Future<?>> futures = new ArrayList<>();
        try {
            for (int i = 0; i < REQUESTS; i++) {
                String traceId = "trace-" + i + "-" + TraceContext.newTraceId();
                futures.add(pool.submit(() -> {
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        HttpHeaders headers = new HttpHeaders();
                        headers.set(TraceContext.TRACE_ID_HEADER, traceId);
                        ResponseEntity<Map> response = restTemplate.exchange(
                                "http://localhost:" + port + "/api/async",
                                HttpMethod.GET, new HttpEntity<>(headers), Map.class);

                        assertThat(response.getStatusCode().value()).isEqualTo(200);
                        String echoedHeader = response.getHeaders().getFirst(TraceContext.TRACE_ID_HEADER);
                        Object bodyTraceId = response.getBody().get("traceId");
                        Object asyncTraceId = response.getBody().get("asyncTraceId");
                        log.info("request traceId={} header={} body={} async={}",
                                traceId, echoedHeader, bodyTraceId, asyncTraceId);

                        assertThat(echoedHeader).isEqualTo(traceId);
                        assertThat(bodyTraceId).isEqualTo(traceId);
                        assertThat(asyncTraceId).isEqualTo(traceId);
                        seenTraceIds.add(traceId);
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                        throw new AssertionError(e);
                    }
                }));
            }
            go.countDown();
            for (Future<?> future : futures) {
                future.get(60, TimeUnit.SECONDS);
            }
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        // Every request carried a distinct id and none was dropped or crossed.
        assertThat(seenTraceIds).hasSize(REQUESTS);
    }

    @Test
    void missingHeaderGetsAFreshServerGeneratedId() {
        ResponseEntity<Map> response = restTemplate.getForEntity(
                "http://localhost:" + port + "/api/sync", Map.class);
        String echoedHeader = response.getHeaders().getFirst(TraceContext.TRACE_ID_HEADER);
        log.info("server-generated traceId={} body={}", echoedHeader, response.getBody());
        assertThat(echoedHeader).isNotBlank();
        assertThat(response.getBody().get("traceId")).isEqualTo(echoedHeader);
    }
}
