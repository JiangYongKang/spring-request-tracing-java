package com.github.highcumontoa.springrequesttracingjava.tracing;

import jakarta.servlet.AsyncEvent;
import jakarta.servlet.AsyncListener;
import jakarta.servlet.DispatcherType;
import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.stereotype.Component;
import org.springframework.web.filter.OncePerRequestFilter;

import java.io.IOException;

/**
 * Binds a trace id to every inbound request, publishes it to the response
 * header and MDC, and records the request lifecycle.
 *
 * <p>Lifecycle coverage:
 * <ul>
 *   <li><b>sync</b>: {@code finally} after the chain guarantees cleanup even
 *       when a controller throws;</li>
 *   <li><b>async</b> ({@code CompletableFuture} return / {@code startAsync}):
 *       an {@link AsyncListener} finishes the trace on {@code onComplete}, and
 *       the ASYNC redispatch rebinds the same state to the redispatch thread;</li>
 *   <li><b>timeout</b>: {@code onTimeout} records a checkpoint before the
 *       container completes the request;</li>
 *   <li><b>error</b>: {@code onError} records the throwable; the trace id is
 *       therefore never lost on a failing path.</li>
 * </ul>
 * Completion is idempotent, so sync + async + error callbacks cannot double
 * count a request.
 */
@Component
@Order(Ordered.HIGHEST_PRECEDENCE)
public class TraceFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(TraceFilter.class);
    private static final String STATE_ATTRIBUTE = TraceFilter.class.getName() + ".STATE";
    private static final String ASYNC_TIMEOUT_ATTRIBUTE = TraceFilter.class.getName() + ".TIMEOUT";

    private final TraceRegistry registry;

    public TraceFilter(TraceRegistry registry) {
        this.registry = registry;
    }

    @Override
    protected boolean shouldNotFilterAsyncDispatch() {
        // We must run on the ASYNC dispatch too, to rebind the trace to that thread.
        return false;
    }

    @Override
    protected boolean shouldNotFilterErrorDispatch() {
        // Do not re-enter on the container's ERROR dispatch: its wrapped request
        // does not carry our request attribute, and the failure was already
        // recorded by the synchronous unwind (advice + catch + finally).
        return true;
    }

    @Override
    protected void doFilterInternal(HttpServletRequest request, HttpServletResponse response,
                                    FilterChain filterChain) throws ServletException, IOException {
        TraceState state;
        DispatcherType type = request.getDispatcherType();
        boolean initialDispatch = type == DispatcherType.REQUEST;

        if (initialDispatch) {
            String traceId = TraceIds.resolve(request.getHeader(TraceIds.HEADER));
            state = new TraceState(traceId);
            if (isObservabilityPath(request.getRequestURI())) {
                state.excludeFromCounters();
            }
            request.setAttribute(STATE_ATTRIBUTE, state);
            registry.start(state);
            state.addEvent("request-start", request.getMethod() + " " + request.getRequestURI());
            response.setHeader(TraceIds.HEADER, traceId);
        } else {
            // ASYNC redispatch and the ERROR dispatch after an unhandled exception
            // reuse the state created on the initial REQUEST dispatch: same trace
            // id, same node list, same in-flight registry entry. Never replace it.
            state = (TraceState) request.getAttribute(STATE_ATTRIBUTE);
            if (state == null) {
                // Defensive: a dispatch without our initial state.
                state = new TraceState(TraceIds.resolve(request.getHeader(TraceIds.HEADER)));
                request.setAttribute(STATE_ATTRIBUTE, state);
                registry.start(state);
            }
            if (type == DispatcherType.ERROR) {
                Object error = request.getAttribute(
                        "jakarta.servlet.error.exception");
                state.addEvent("error-dispatch",
                        error == null ? String.valueOf(
                                request.getAttribute("jakarta.servlet.error.status_code"))
                                : error.toString());
            } else {
                state.addEvent("async-redispatch", type.name());
            }
        }

        TraceContext.bind(state);
        try {
            filterChain.doFilter(request, response);
            if (initialDispatch) {
                if (request.isAsyncStarted()) {
                    configureAsync(request, response, state);
                } else {
                    finish(state, request, response, null);
                }
            } else if (type == DispatcherType.ERROR) {
                // The initial REQUEST dispatch's exception path unwinds without
                // reaching our normal completion; finish on the ERROR dispatch.
                finish(state, request, response, null);
            }
            // On an ASYNC dispatch, onComplete (registered at the initial dispatch)
            // is responsible for finishing the trace.
        } catch (Exception ex) {
            // Exceptions that escape the chain on the initial dispatch. Completion
            // is idempotent, so a following ERROR dispatch cannot double-count.
            if (initialDispatch && !request.isAsyncStarted()) {
                state.addEvent("filter-error", ex.toString());
                finish(state, request, response, ex);
            }
            throw ex;
        } finally {
            TraceContext.unbind();
        }
    }

    private boolean isObservabilityPath(String uri) {
        return uri != null
                && (uri.startsWith("/observability") || uri.startsWith("/actuator"));
    }

    private void configureAsync(HttpServletRequest request, HttpServletResponse response,
                                TraceState state) {
        state.addEvent("async-started", null);
        // Capture the request/response now: after the async context completes it
        // is illegal to obtain them back from the event, and onComplete must
        // still be able to finish the trace.
        request.getAsyncContext().addListener(new AsyncListener() {
            @Override
            public void onComplete(AsyncEvent event) {
                finish(state, request, response, event.getThrowable());
            }

            @Override
            public void onTimeout(AsyncEvent event) {
                TraceContext.runWith(state, () -> {
                    request.setAttribute(ASYNC_TIMEOUT_ATTRIBUTE, true);
                    state.addEvent("async-timeout",
                            "timeoutMs=" + event.getAsyncContext().getTimeout());
                    log.warn("async request timed out");
                });
            }

            @Override
            public void onError(AsyncEvent event) {
                state.addEvent("async-error",
                        event.getThrowable() == null ? "unknown" : event.getThrowable().toString());
            }

            @Override
            public void onStartAsync(AsyncEvent event) {
                // No-op: the initial start is recorded by the filter.
            }
        });
    }

    /**
     * Finish exactly once: detach the in-flight state, archive it, count the
     * result and log a terminal line. Idempotent through {@link TraceState}.
     *
     * <p>The state is bound to the finishing (possibly async-callback) thread
     * just for this method, so the terminal log line is MDC-populated; it is
     * always cleared in {@code finally}, leaving no residue on a container
     * thread.
     */
    private void finish(TraceState state, HttpServletRequest request,
                        HttpServletResponse response, Throwable failure) {
        if (state.isFinished()) {
            return;
        }
        TraceContext.bind(state);
        try {
            boolean failed = failure != null
                    || response.getStatus() >= 500
                    || Boolean.TRUE.equals(request.getAttribute(ASYNC_TIMEOUT_ATTRIBUTE));
            if (failure != null) {
                state.addEvent("request-error", failure.toString());
            }
            registry.complete(state.requestId(), failed);
            if (failed) {
                log.warn("request finished with failure status={} nodes={}",
                        response.getStatus(), state.events().size());
            } else {
                log.info("request finished status={} nodes={}",
                        response.getStatus(), state.events().size());
            }
        } finally {
            TraceContext.unbind();
        }
    }
}
