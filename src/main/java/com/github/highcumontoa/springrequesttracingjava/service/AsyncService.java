package com.github.highcumontoa.springrequesttracingjava.service;

import com.github.highcumontoa.springrequesttracingjava.config.AsyncConfig;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.scheduling.annotation.Async;
import org.springframework.stereotype.Service;

import java.util.concurrent.CompletableFuture;

/**
 * Demonstrates that the trace id survives a thread switch into {@code @Async} work.
 */
@Service
public class AsyncService {

    private static final Logger log = LoggerFactory.getLogger(AsyncService.class);

    /**
     * Runs on the async executor; the returned future carries the trace id as
     * observed on the worker thread, proving propagation across the thread switch.
     */
    @Async(AsyncConfig.TASK_EXECUTOR)
    public CompletableFuture<String> traceIdOnWorkerThread() {
        String traceId = TraceContext.currentTraceId();
        log.info("async node reached thread={}", Thread.currentThread().getName());
        return CompletableFuture.completedFuture(traceId);
    }

    /**
     * Async failure path: the exception is captured, logged with the trace id,
     * and the id observed on the worker thread is still returned.
     */
    @Async(AsyncConfig.TASK_EXECUTOR)
    public CompletableFuture<String> traceIdAfterCaughtFailure() {
        String traceId = TraceContext.currentTraceId();
        try {
            throw new IllegalStateException("simulated async failure");
        } catch (IllegalStateException e) {
            log.error("async failure caught and traced: {}", e.getMessage());
        }
        return CompletableFuture.completedFuture(traceId);
    }
}
