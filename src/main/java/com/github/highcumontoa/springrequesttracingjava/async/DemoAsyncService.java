package com.github.highcumontoa.springrequesttracingjava.async;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/** Simulated asynchronous work that must keep the inbound trace id. */
@Service
public class DemoAsyncService {

    private static final Logger log = LoggerFactory.getLogger(DemoAsyncService.class);

    /**
     * Runs on the {@code traceExecutor} pool. The trace id is bound by the
     * executor's task decorator before this method body executes, so
     * {@link TraceContext#currentTraceId()} and MDC logs are populated here.
     */
    @Async("traceExecutor")
    public CompletableFuture<String> step(String label) {
        String traceId = TraceContext.currentTraceId();
        log.info("async step begin traceId={} label={}", traceId, label);
        TraceContext.event("async-step-begin", label);
        try {
            Thread.sleep(20);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TraceContext.event("async-step-interrupted", label);
            return CompletableFuture.failedFuture(e);
        }
        TraceContext.event("async-step-end", label);
        log.info("async step end label={}", traceId);
        return CompletableFuture.completedFuture("done:" + label + ":" + traceId);
    }

    /** Async work that fails on a worker thread; the trace id must survive it. */
    @Async("traceExecutor")
    public CompletableFuture<String> failingStep(String label) {
        String traceId = TraceContext.currentTraceId();
        log.info("async failing step begin label={}", traceId);
        TraceContext.event("async-failing-begin", label);
        CompletableFuture<String> failed = CompletableFuture.failedFuture(
                new IllegalStateException("async failure for trace " + traceId));
        TraceContext.event("async-failing-end", label);
        return failed;
    }

    /** Async work that runs longer than the container's async request timeout. */
    @Async("traceExecutor")
    public CompletableFuture<String> slowStep(String label, long millis) {
        String traceId = TraceContext.currentTraceId();
        log.info("async slow step begin label={} millis={}", traceId, millis);
        TraceContext.event("async-slow-begin", label + " millis=" + millis);
        try {
            Thread.sleep(millis);
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            TraceContext.event("async-slow-interrupted", label);
            return CompletableFuture.failedFuture(e);
        }
        TraceContext.event("async-slow-end", label);
        return CompletableFuture.completedFuture("slow:" + traceId);
    }
}
