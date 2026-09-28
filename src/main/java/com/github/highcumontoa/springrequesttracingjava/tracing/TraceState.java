package com.github.highcumontoa.springrequesttracingjava.tracing;

import com.fasterxml.jackson.annotation.JsonProperty;

import java.util.List;
import java.util.UUID;
import java.util.concurrent.CopyOnWriteArrayList;

/**
 * Mutable state of one inbound request: its public trace id, a unique internal
 * request id, and the ordered nodes it visited.
 *
 * <p>The {@code traceId} is propagated across hops (sync / async / outbound)
 * and may therefore be shared by a nested inbound call; {@code requestId} is
 * unique per inbound request and is what the registry keys in-flight state by,
 * so a nested call sharing a trace id can never evict the outer request's state.
 *
 * <p>The event list is a {@link CopyOnWriteArrayList}: events may be appended
 * from worker threads (async tasks) while the request thread is still running.
 */
public final class TraceState {

    private final String traceId;
    private final String requestId = UUID.randomUUID().toString();
    private final long startNanos = System.nanoTime();
    private final CopyOnWriteArrayList<TraceEvent> events = new CopyOnWriteArrayList<>();
    private volatile boolean finished;
    private volatile boolean failed;
    private volatile boolean counted = true;

    public TraceState(String traceId) {
        this.traceId = traceId;
    }

    /**
     * Mark this request as internal observability traffic: it still carries a
     * trace id and records events, but it is excluded from public counters
     * (so that querying metrics cannot itself move the metrics).
     */
    public void excludeFromCounters() {
        this.counted = false;
    }

    public boolean isCounted() {
        return counted;
    }

    /** Public, propagated correlation identifier. */
    @JsonProperty("traceId")
    public String traceId() {
        return traceId;
    }

    /** Unique per inbound request; used as the internal registry key. */
    @JsonProperty("requestId")
    public String requestId() {
        return requestId;
    }

    /** Mark the request finished, recording a terminal checkpoint. */
    public void markFinished(boolean failed) {
        this.finished = true;
        this.failed = failed;
        addEvent(failed ? "request-failed" : "request-complete",
                "elapsedMs=" + elapsedMillis());
    }

    @JsonProperty("finished")
    public boolean isFinished() {
        return finished;
    }

    @JsonProperty("failed")
    public boolean isFailed() {
        return failed;
    }

    /** Append a checkpoint. Never throws into the caller's business logic. */
    public void addEvent(String node, String detail) {
        events.add(TraceEvent.of(node, detail));
    }

    /** Snapshot of all checkpoints reached so far, in arrival order. */
    @JsonProperty("events")
    public List<TraceEvent> events() {
        return List.copyOf(events);
    }

    /** Elapsed milliseconds since this trace was created. */
    @JsonProperty("elapsedMillis")
    public long elapsedMillis() {
        return (System.nanoTime() - startNanos) / 1_000_000L;
    }
}
