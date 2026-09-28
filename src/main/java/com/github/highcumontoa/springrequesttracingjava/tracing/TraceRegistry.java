package com.github.highcumontoa.springrequesttracingjava.tracing;

import org.springframework.beans.factory.annotation.Value;
import org.springframework.stereotype.Component;

import java.util.ArrayDeque;
import java.util.List;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Process-wide store of trace states and request counters.
 *
 * <p><b>Identity:</b> in-flight state is keyed by each inbound request's unique
 * {@code requestId}, never by the (shared across hops) {@code traceId}. This
 * keeps nested calls that carry the same trace id — e.g. a service calling
 * itself, or a downstream service calling back — from overwriting each other.
 * Lookup by trace id searches in-flight and archived states.
 *
 * <p><b>Bounded memory:</b> finished traces are retained in a fixed-capacity FIFO
 * buffer; once the capacity is reached the oldest trace is evicted. In-flight
 * states are removed on completion of <em>every</em> request (success,
 * exception, container timeout and async completion alike). Counters are
 * {@link AtomicLong}s and never hold per-request objects.
 */
@Component
public class TraceRegistry {

    /** Traces currently being processed, keyed by unique per-request id. */
    private final ConcurrentHashMap<String, TraceState> inFlight = new ConcurrentHashMap<>();

    /** Finished traces kept for post-mortem lookup, oldest evicted first. */
    private final ArrayDeque<TraceState> recent = new ArrayDeque<>();
    private final int recentCapacity;

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();

    public TraceRegistry(@Value("${tracing.recent-capacity:200}") int recentCapacity) {
        this.recentCapacity = Math.max(1, recentCapacity);
    }

    /** Register a request that has just entered the filter. */
    public void start(TraceState state) {
        inFlight.put(state.requestId(), state);
        if (state.isCounted()) {
            total.incrementAndGet();
        }
    }

    /**
     * Close a request: detach its in-flight state (freeing the map entry) and
     * archive it into the bounded buffer. Idempotent: a duplicate callback for
     * the same {@code requestId} (e.g. error + complete) is counted once.
     *
     * @param failed whether the request ended with an exception / error status
     */
    public void complete(String requestId, boolean failed) {
        TraceState state = inFlight.remove(requestId);
        if (state == null) {
            return;
        }
        state.markFinished(failed);
        archive(state);
        if (failed && state.isCounted()) {
            this.failed.incrementAndGet();
        }
    }

    private synchronized void archive(TraceState state) {
        recent.addLast(state);
        while (recent.size() > recentCapacity) {
            recent.removeFirst();
        }
    }

    /**
     * Look states up by public trace id, whether in flight or archived.
     * When several requests share one trace id (nested/propagated calls), the
     * most recently finished match is returned — the caller's own outer span.
     */
    public TraceState get(String traceId) {
        for (TraceState state : inFlight.values()) {
            if (state.traceId().equals(traceId)) {
                return state;
            }
        }
        synchronized (this) {
            TraceState match = null;
            for (TraceState candidate : recent) {
                if (candidate.traceId().equals(traceId)) {
                    match = candidate; // keep walking: prefer the newest match
                }
            }
            return match;
        }
    }

    /** Snapshot of archived traces, oldest first. */
    public synchronized List<TraceState> recent() {
        return List.copyOf(recent);
    }

    /** Total number of requests entered since start-up (cumulative). */
    public long totalRequests() {
        return total.get();
    }

    /** Total number of requests that finished with a failure (cumulative). */
    public long failedRequests() {
        return failed.get();
    }

    /** Number of requests currently being processed. */
    public int inFlight() {
        return inFlight.size();
    }

    /**
     * In-flight count excluding one specific request — used by the metrics
     * endpoint so that the very request answering the query is not counted as
     * "in flight" (it is still running inside the filter chain at that moment).
     */
    public int inFlightExcluding(String requestId) {
        return requestId == null ? inFlight.size()
                : inFlight.size() - (inFlight.containsKey(requestId) ? 1 : 0);
    }
}
