package com.github.highcumontoa.springrequesttracingjava.tracing;

import java.util.UUID;

/**
 * Holds the trace identifier bound to the current request thread.
 *
 * <p>The id is stored in a {@link ThreadLocal}; callers must invoke {@link #clear()}
 * when the request finishes so pooled threads never leak ids across requests and
 * ids never accumulate in memory.
 */
public final class TraceContext {

    /** HTTP header used to propagate the trace id across process boundaries. */
    public static final String TRACE_ID_HEADER = "X-Trace-Id";
    /** Logback/SLF4J MDC key so every log line carries the trace id. */
    public static final String MDC_KEY = "traceId";

    private static final ThreadLocal<String> CURRENT = new ThreadLocal<>();

    private TraceContext() {
    }

    /** Returns the trace id bound to the current thread, or {@code null} if none. */
    public static String currentTraceId() {
        return CURRENT.get();
    }

    /** Binds the given trace id to the current thread. */
    public static void bind(String traceId) {
        CURRENT.set(traceId);
    }

    /** Removes the trace id from the current thread. Must be called in a finally block. */
    public static void clear() {
        CURRENT.remove();
    }

    /** Generates a new unique trace id. */
    public static String newTraceId() {
        return UUID.randomUUID().toString();
    }
}
