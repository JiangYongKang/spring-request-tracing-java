package com.github.highcumontoa.springrequesttracingjava.tests;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceIds;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceState;
import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.Test;
import org.slf4j.MDC;

import java.util.concurrent.ExecutorService;
import java.util.concurrent.Executors;
import java.util.concurrent.Future;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

class TraceContextTest {

    @AfterEach
    void clear() {
        TraceContext.unbind();
    }

    @Test
    void bindPublishesIdToMdcAndUnbindClearsIt() {
        TraceState state = new TraceState(TraceIds.generate());
        TraceContext.bind(state);

        assertThat(TraceContext.currentTraceId()).isEqualTo(state.traceId());
        assertThat(MDC.get(TraceIds.MDC_KEY)).isEqualTo(state.traceId());

        TraceContext.unbind();
        assertThat(TraceContext.currentTraceId()).isNull();
        assertThat(MDC.get(TraceIds.MDC_KEY)).isNull();
    }

    @Test
    void runWithRestoresPreviousBindingEvenWhenTaskThrows() {
        TraceState outer = new TraceState("outer-id-12345");
        TraceState inner = new TraceState("inner-id-12345");
        TraceContext.bind(outer);

        assertThatThrownBy(() -> TraceContext.runWith(inner, () -> {
            throw new IllegalStateException("boom");
        })).isInstanceOf(IllegalStateException.class);

        // Exception must not sever the outer binding or leave the inner id in MDC.
        assertThat(TraceContext.current()).isSameAs(outer);
        assertThat(MDC.get(TraceIds.MDC_KEY)).isEqualTo("outer-id-12345");
    }

    @Test
    void asyncHandOffSeesSameStateAndDoesNotLeakBack() throws Exception {
        TraceState state = new TraceState(TraceIds.generate());
        ExecutorService pool = Executors.newSingleThreadExecutor();
        try {
            Future<String> future = pool.submit(() ->
                    TraceContext.callWith(state, () -> {
                        TraceContext.event("pool-node", "hi");
                        return TraceContext.currentTraceId();
                    }));

            assertThat(future.get()).isEqualTo(state.traceId());
            assertThat(state.events()).extracting(e -> e.node()).contains("pool-node");

            // After the task, the pooled thread must be clean again.
            Future<String> after = pool.submit(TraceContext::currentTraceId);
            assertThat(after.get()).isNull();
        } finally {
            pool.shutdownNow();
        }
    }

    @Test
    void eventWithoutBindingIsANoOp() {
        // Should simply return without throwing.
        TraceContext.event("orphan", null);
        assertThat(TraceContext.currentTraceId()).isNull();
    }
}
