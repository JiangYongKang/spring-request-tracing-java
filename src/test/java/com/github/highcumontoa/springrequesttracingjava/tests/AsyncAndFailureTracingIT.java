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

import java.util.Map;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Verifies the trace id survives async hops, caught async failures, thrown
 * exceptions and outbound calls.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
class AsyncAndFailureTracingIT {

    private static final Logger log = LoggerFactory.getLogger(AsyncAndFailureTracingIT.class);

    @LocalServerPort
    int port;

    @Autowired
    TestRestTemplate restTemplate;

    @Test
    void asyncFailurePathKeepsTheTraceId() {
        String traceId = "async-fail-" + TraceContext.newTraceId();
        ResponseEntity<Map> response = exchange("/api/async-failure", traceId);
        log.info("async-failure traceId={} body={}", traceId, response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("traceId")).isEqualTo(traceId);
        assertThat(response.getBody().get("asyncTraceId")).isEqualTo(traceId);
    }

    @Test
    void thrownExceptionStillCarriesTheTraceId() {
        String traceId = "fail-" + TraceContext.newTraceId();
        ResponseEntity<Map> response = exchange("/api/fail", traceId);
        log.info("fail traceId={} status={} body={}", traceId, response.getStatusCode(), response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(500);
        // The error response echoes the id so the failure can be looked up in logs.
        assertThat(response.getBody().get("traceId")).isEqualTo(traceId);
        // The response header still carries the id even on the error path.
        assertThat(response.getHeaders().getFirst(TraceContext.TRACE_ID_HEADER)).isEqualTo(traceId);
    }

    @Test
    void outboundCallForwardsTheTraceId() {
        String traceId = "outbound-" + TraceContext.newTraceId();
        String downstreamUrl = "http://localhost:" + port + "/api/sync";
        ResponseEntity<Map> response = exchange("/api/outbound?url=" + downstreamUrl, traceId);
        log.info("outbound traceId={} body={}", traceId, response.getBody());
        assertThat(response.getStatusCode().value()).isEqualTo(200);
        assertThat(response.getBody().get("traceId")).isEqualTo(traceId);
        // The downstream service echoed back the very same id it received.
        assertThat(response.getBody().get("downstreamTraceId")).isEqualTo(traceId);
    }

    private ResponseEntity<Map> exchange(String path, String traceId) {
        HttpHeaders headers = new HttpHeaders();
        headers.set(TraceContext.TRACE_ID_HEADER, traceId);
        return restTemplate.exchange("http://localhost:" + port + path,
                HttpMethod.GET, new HttpEntity<>(headers), Map.class);
    }
}
