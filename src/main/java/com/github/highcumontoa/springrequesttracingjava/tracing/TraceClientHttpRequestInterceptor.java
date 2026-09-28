package com.github.highcumontoa.springrequesttracingjava.tracing;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;

import java.io.IOException;

/**
 * Adds the current trace id to outbound HTTP calls so downstream services can
 * continue the same trace.
 */
public class TraceClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TraceClientHttpRequestInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body, ClientHttpRequestExecution execution)
            throws IOException {
        String traceId = TraceContext.currentTraceId();
        if (traceId != null && !request.getHeaders().containsKey(TraceContext.TRACE_ID_HEADER)) {
            request.getHeaders().set(TraceContext.TRACE_ID_HEADER, traceId);
        }
        log.info("outbound call method={} uri={}", request.getMethod(), request.getURI());
        return execution.execute(request, body);
    }
}
