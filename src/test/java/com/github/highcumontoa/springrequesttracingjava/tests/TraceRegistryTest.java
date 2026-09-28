package com.github.highcumontoa.springrequesttracingjava.tests;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceIds;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceRegistry;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceState;
import org.junit.jupiter.api.Test;

import java.util.ArrayList;
import java.util.List;
import java.util.concurrent.Callable;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;

class TraceRegistryTest {

    @Test
    void countersTrackStartCompleteAndFailure() {
        TraceRegistry registry = new TraceRegistry(10);

        TraceState a = new TraceState(TraceIds.generate());
        TraceState b = new TraceState(TraceIds.generate());
        registry.start(a);
        registry.start(b);
        assertThat(registry.inFlight()).isEqualTo(2);
        assertThat(registry.totalRequests()).isEqualTo(2);

        registry.complete(a.requestId(), false);
        registry.complete(b.requestId(), true);

        assertThat(registry.inFlight()).isZero();
        assertThat(registry.totalRequests()).isEqualTo(2);
        assertThat(registry.failedRequests()).isEqualTo(1);
        assertThat(registry.get(a.traceId()).isFinished()).isTrue();
        assertThat(registry.get(b.traceId()).isFailed()).isTrue();
    }

    @Test
    void finishedTracesAreBoundedByFifoEviction() {
        TraceRegistry registry = new TraceRegistry(3);
        List<String> ids = new ArrayList<>();

        for (int i = 0; i < 10; i++) {
            TraceState state = new TraceState("trace-" + String.format("%02d", i) + "-abcd");
            ids.add(state.traceId());
            registry.start(state);
            registry.complete(state.requestId(), false);
        }

        assertThat(registry.recent()).hasSize(3);
        // Oldest ones evicted...
        assertThat(registry.get(ids.get(0))).isNull();
        assertThat(registry.get(ids.get(6))).isNull();
        // ...newest three retained.
        assertThat(registry.get(ids.get(7))).isNotNull();
        assertThat(registry.get(ids.get(9))).isNotNull();
        // Counters still see every request; memory does not grow with traffic.
        assertThat(registry.totalRequests()).isEqualTo(10);
    }

    @Test
    void concurrentStartsAndCompletesKeepCountersConsistent() throws Exception {
        TraceRegistry registry = new TraceRegistry(50);
        int threads = 16;
        int perThread = 500;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        try {
            List<Callable<Void>> tasks = new ArrayList<>();
            for (int t = 0; t < threads; t++) {
                boolean failing = t % 2 == 0;
                tasks.add(() -> {
                    for (int i = 0; i < perThread; i++) {
                        TraceState state = new TraceState(TraceIds.generate());
                        registry.start(state);
                        registry.complete(state.requestId(), failing);
                    }
                    return null;
                });
            }
            for (Future<Void> f : pool.invokeAll(tasks)) {
                f.get();
            }
        } finally {
            pool.shutdown();
        }

        int total = threads * perThread;
        assertThat(registry.totalRequests()).isEqualTo(total);
        assertThat(registry.failedRequests()).isEqualTo(total / 2);
        assertThat(registry.inFlight()).isZero();
        assertThat(registry.recent()).hasSizeLessThanOrEqualTo(50);
    }

    @Test
    void doubleCompleteIsHarmlessAndCountedOnce() {
        TraceRegistry registry = new TraceRegistry(5);
        TraceState state = new TraceState(TraceIds.generate());
        registry.start(state);
        registry.complete(state.requestId(), true);
        registry.complete(state.requestId(), true);

        assertThat(registry.totalRequests()).isEqualTo(1);
        assertThat(registry.failedRequests()).isEqualTo(1);
        assertThat(registry.inFlight()).isZero();
    }
}
