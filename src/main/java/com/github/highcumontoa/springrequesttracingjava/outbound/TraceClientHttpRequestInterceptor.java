package com.github.highcumontoa.springrequesttracingjava.outbound;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceIds;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.HttpRequest;
import org.springframework.http.client.ClientHttpRequestExecution;
import org.springframework.http.client.ClientHttpRequestInterceptor;
import org.springframework.http.client.ClientHttpResponse;
import org.springframework.stereotype.Component;

import java.io.IOException;

/**
 * Copies the current trace id onto every outbound HTTP request and records an
 * {@code outbound-call} checkpoint with method/URL and outcome.
 *
 * <p>When invoked from an async worker thread the id is the same one captured by
 * the task decorator; a missing binding is tolerated (no header is added) rather
 * than failing the call.
 */
@Component
public class TraceClientHttpRequestInterceptor implements ClientHttpRequestInterceptor {

    private static final Logger log = LoggerFactory.getLogger(TraceClientHttpRequestInterceptor.class);

    @Override
    public ClientHttpResponse intercept(HttpRequest request, byte[] body,
                                        ClientHttpRequestExecution execution) throws IOException {
        String traceId = TraceContext.currentTraceId();
        if (traceId != null) {
            request.getHeaders().set(TraceIds.HEADER, traceId);
        }
        String target = request.getMethod() + " " + request.getURI();
        TraceContext.event("outbound-start", target);
        log.info("outbound call begin traceId={} target={}", traceId, target);
        try {
            ClientHttpResponse response = execution.execute(request, body);
            TraceContext.event("outbound-end", target + " status=" + response.getStatusCode());
            return response;
        } catch (IOException | RuntimeException ex) {
            TraceContext.event("outbound-error", target + " " + ex);
            throw ex;
        }
    }
}
