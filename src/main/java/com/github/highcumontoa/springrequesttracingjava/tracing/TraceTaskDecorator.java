package com.github.highcumontoa.springrequesttracingjava.tracing;

import org.slf4j.MDC;
import org.springframework.core.task.TaskDecorator;

/**
 * Propagates the trace context from the submitting thread to async worker threads.
 *
 * <p>The trace id is captured when the task is submitted and re-bound before the
 * task runs; the worker thread is cleaned up in a {@code finally} block so pooled
 * threads never carry a stale id into the next task.
 */
public class TraceTaskDecorator implements TaskDecorator {

    @Override
    public Runnable decorate(Runnable runnable) {
        String traceId = TraceContext.currentTraceId();
        return () -> {
            String previous = TraceContext.currentTraceId();
            try {
                if (traceId != null) {
                    TraceContext.bind(traceId);
                    MDC.put(TraceContext.MDC_KEY, traceId);
                }
                runnable.run();
            } finally {
                if (traceId != null) {
                    if (previous != null) {
                        TraceContext.bind(previous);
                        MDC.put(TraceContext.MDC_KEY, previous);
                    } else {
                        TraceContext.clear();
                        MDC.remove(TraceContext.MDC_KEY);
                    }
                }
            }
        };
    }
}
