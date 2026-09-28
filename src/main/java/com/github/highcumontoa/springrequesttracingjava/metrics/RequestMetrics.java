package com.github.highcumontoa.springrequesttracingjava.metrics;

import org.springframework.stereotype.Component;

import java.util.concurrent.atomic.AtomicLong;

/**
 * Thread-safe counters for total, failed and in-flight requests.
 *
 * <p>All counters are monotonic {@link AtomicLong}s except {@code inFlight},
 * which is decremented when a request finishes. Because increment/decrement
 * are paired in the filter's try/finally, the in-flight gauge always returns
 * to zero under sustained concurrency and never grows without bound.
 */
@Component
public class RequestMetrics {

    private final AtomicLong total = new AtomicLong();
    private final AtomicLong failed = new AtomicLong();
    private final AtomicLong inFlight = new AtomicLong();

    /** Marks the beginning of a request. */
    public void onRequestStart() {
        total.incrementAndGet();
        inFlight.incrementAndGet();
    }

    /** Marks the end of a request. */
    public void onRequestEnd(boolean failedRequest) {
        if (failedRequest) {
            failed.incrementAndGet();
        }
        inFlight.decrementAndGet();
    }

    public long getTotal() {
        return total.get();
    }

    public long getFailed() {
        return failed.get();
    }

    public long getInFlight() {
        return inFlight.get();
    }
}
