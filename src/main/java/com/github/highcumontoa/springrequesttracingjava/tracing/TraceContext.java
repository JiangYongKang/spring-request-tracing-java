package com.github.highcumontoa.springrequesttracingjava.tracing;

import org.slf4j.MDC;

import java.util.function.Supplier;

/**
 * Thread-bound holder of the current {@link TraceState}.
 *
 * <p>Storage is a plain (non-inheritable) {@link ThreadLocal}: trace state is
 * handed to async / pooled threads explicitly via {@link #runWith} (see
 * {@code TracingAsyncConfig}), so a recycled worker thread can never inherit a
 * stale id from a previous task. The id is also published to SLF4J {@link MDC}
 * so every log line on the bound thread carries it.
 *
 * <p>All mutating methods are exception-safe: they restore the previous binding
 * in a {@code finally} block even when the task throws.
 */
public final class TraceContext {

    private static final ThreadLocal<TraceState> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /** Bind the given state to the calling thread and publish its id to MDC. */
    public static void bind(TraceState state) {
        CURRENT.set(state);
        if (state != null) {
            MDC.put(TraceIds.MDC_KEY, state.traceId());
        } else {
            MDC.remove(TraceIds.MDC_KEY);
        }
    }

    /** Remove any binding from the calling thread and clear MDC. */
    public static void unbind() {
        CURRENT.remove();
        MDC.remove(TraceIds.MDC_KEY);
    }

    public static TraceState current() {
        return CURRENT.get();
    }

    public static String currentTraceId() {
        TraceState state = CURRENT.get();
        return state == null ? null : state.traceId();
    }

    /** Unique per-request internal id of the current request, or {@code null}. */
    public static String currentRequestId() {
        TraceState state = CURRENT.get();
        return state == null ? null : state.requestId();
    }

    /**
     * Record a checkpoint on the current trace, if one is bound. Safe to call
     * from any layer; does nothing (and never throws) when unbound.
     */
    public static void event(String node, String detail) {
        TraceState state = CURRENT.get();
        if (state != null) {
            state.addEvent(node, detail);
        }
    }

    /** Run {@code task} with {@code state} bound, restoring the previous binding afterwards. */
    public static <T> T callWith(TraceState state, Supplier<T> task) {
        TraceState previous = CURRENT.get();
        bind(state);
        try {
            return task.get();
        } finally {
            if (previous == null) {
                unbind();
            } else {
                bind(previous);
            }
        }
    }

    /** Run {@code task} with {@code state} bound, restoring the previous binding afterwards. */
    public static void runWith(TraceState state, Runnable task) {
        callWith(state, () -> {
            task.run();
            return null;
        });
    }
}
