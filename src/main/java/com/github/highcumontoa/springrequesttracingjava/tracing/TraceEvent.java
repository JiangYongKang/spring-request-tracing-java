package com.github.highcumontoa.springrequesttracingjava.tracing;

import java.time.Instant;

/**
 * An immutable checkpoint that a request passed through.
 *
 * @param time   when the checkpoint was reached
 * @param node   stable name of the node, e.g. {@code filter-start}, {@code async-step}
 * @param detail optional human-readable detail (must not contain user secrets)
 */
public record TraceEvent(Instant time, String node, String detail) {

    public static TraceEvent of(String node, String detail) {
        return new TraceEvent(Instant.now(), node, detail);
    }
}
