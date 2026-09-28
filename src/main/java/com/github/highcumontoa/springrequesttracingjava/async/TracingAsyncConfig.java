package com.github.highcumontoa.springrequesttracingjava.async;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceContext;
import com.github.highcumontoa.springrequesttracingjava.tracing.TraceState;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.task.TaskDecorator;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;
import java.util.concurrent.ThreadPoolExecutor;

/**
 * Async executors that hand the current trace state to worker threads.
 *
 * <p>The decorator captures the caller's {@link TraceState} at <em>submission</em>
 * time (on the request thread) and binds it on the worker thread for the duration
 * of the task, clearing it afterwards. Pooled threads therefore start and end
 * every task with an empty context — no cross-task leakage.
 */
@Configuration
@EnableAsync
public class TracingAsyncConfig {

    @Bean
    public TaskDecorator traceTaskDecorator() {
        return runnable -> {
            TraceState callerState = TraceContext.current();
            return () -> {
                if (callerState == null) {
                    runnable.run();
                    return;
                }
                TraceContext.bind(callerState);
                try {
                    runnable.run();
                } finally {
                    TraceContext.unbind();
                }
            };
        };
    }

    @Bean(name = "traceExecutor")
    public Executor traceExecutor(TaskDecorator traceTaskDecorator) {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(traceTaskDecorator);
        // A saturated pool runs the task on the caller thread; the decorator
        // still applies, so the trace id is preserved either way.
        executor.setRejectedExecutionHandler(new ThreadPoolExecutor.CallerRunsPolicy());
        executor.initialize();
        return executor;
    }
}
