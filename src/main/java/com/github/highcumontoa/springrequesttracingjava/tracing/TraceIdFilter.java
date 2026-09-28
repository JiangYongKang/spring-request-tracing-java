package com.github.highcumontoa.springrequesttracingjava.tracing;

import com.github.highcumontoa.springrequesttracingjava.metrics.RequestMetrics;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.slf4j.MDC;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds a trace id to every incoming request and records request metrics.
 *
 * <p>Rules:
 * <ul>
 *   <li>If the caller supplies {@code X-Trace-Id}, it is honoured (bounded length);
 *       otherwise a fresh UUID is generated.</li>
 *   <li>The id is bound to {@link TraceContext} and the SLF4J MDC so every log line
 *       of this request carries it, and echoed back in the response header.</li>
 *   <li>Cleanup happens in a {@code finally} block, so neither exceptions nor
 *       timeouts leak the id into a pooled thread or lose it mid-request.</li>
 * </ul>
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceIdFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceIdFilter.class);
    private static final int MAX_INCOMING_TRACE_ID_LENGTH = 128;

    private final RequestMetrics metrics;

    public TraceIdFilter(RequestMetrics metrics) {
        this.metrics = metrics;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response, FilterChain filterChain)
            throws ServletException, IOException {
        String traceId = resolveTraceId(request);
        TraceContext.bind(traceId);
        MDC.put(TraceContext.MDC_KEY, traceId);
        response.setHeader(TraceContext.TRACE_ID_HEADER, traceId);
        metrics.onRequestStart();
        log.info("request started method={} path={}", request.getMethod(), request.getRequestURI());
        boolean failed = false;
        try {
            filterChain.doFilter(request, response);
            failed = response.getStatus() >= 500;
        } catch (ServletException | IOException | RuntimeException | Error e) {
            failed = true;
            log.error("request failed method={} path={} error={}", request.getMethod(), request.getRequestURI(),
                    e.toString());
            throw e;
        } finally {
            metrics.onRequestEnd(failed);
            log.info("request finished method={} path={} status={} failed={}",
                    request.getMethod(), request.getRequestURI(), response.getStatus(), failed);
            TraceContext.clear();
            MDC.remove(TraceContext.MDC_KEY);
        }
    }

    private String resolveTraceId(HttpServletRequest request) {
        String incoming = request.getHeader(TraceContext.TRACE_ID_HEADER);
        if (incoming != null && !incoming.isBlank() && incoming.length() <= MAX_INCOMING_TRACE_ID_LENGTH) {
            return incoming.trim();
        }
        return TraceContext.newTraceId();
    }
}
