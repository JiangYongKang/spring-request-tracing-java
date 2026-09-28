package com.github.highcumontoa.springrequesttracingjava.tests;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;

import java.util.Set;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CountDownLatch;
import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.TimeUnit;

import static org.assertj.core.api.Assertions.assertThat;

/**
 * Unit tests for the thread-bound trace id holder.
 */
class TraceContextTest {

    private static final Logger log = LoggerFactory.getLogger(TraceContextTest.class);

    @AfterEach
    void cleanup() {
        TraceContext.clear();
    }

    @Test
    void generatedIdsAreUnique() {
        Set<String> ids = ConcurrentHashMap.newKeySet();
        for (int i = 0; i < 10_000; i++) {
            ids.add(TraceContext.newTraceId());
        }
        log.info("generated {} unique ids", ids.size());
        assertThat(ids).hasSize(10_000);
    }

    @Test
    void clearRemovesTheIdSoNothingAccumulates() {
        TraceContext.bind(TraceContext.newTraceId());
        assertThat(TraceContext.currentTraceId()).isNotNull();
        TraceContext.clear();
        assertThat(TraceContext.currentTraceId()).isNull();
    }

    @Test
    void idsDoNotCrossBetweenConcurrentThreads() throws Exception {
        int threads = 16;
        ExecutorService pool = Executors.newFixedThreadPool(threads);
        CountDownLatch ready = new CountDownLatch(threads);
        CountDownLatch go = new CountDownLatch(1);
        Set<String> mismatches = ConcurrentHashMap.newKeySet();
        try {
            for (int i = 0; i < threads; i++) {
                pool.submit(() -> {
                    String id = TraceContext.newTraceId();
                    TraceContext.bind(id);
                    ready.countDown();
                    try {
                        go.await(5, TimeUnit.SECONDS);
                        // Spin a little so threads overlap while holding their ids.
                        for (int j = 0; j < 1000; j++) {
                            if (!id.equals(TraceContext.currentTraceId())) {
                                mismatches.add(id + " saw " + TraceContext.currentTraceId());
                                break;
                            }
                        }
                    } catch (InterruptedException e) {
                        Thread.currentThread().interrupt();
                    } finally {
                        TraceContext.clear();
                    }
                    log.info("thread {} done with traceId={}", Thread.currentThread().getName(), id);
                });
            }
            assertThat(ready.await(10, TimeUnit.SECONDS)).isTrue();
            go.countDown();
        } finally {
            pool.shutdown();
            assertThat(pool.awaitTermination(10, TimeUnit.SECONDS)).isTrue();
        }
        assertThat(mismatches).isEmpty();
    }
}
