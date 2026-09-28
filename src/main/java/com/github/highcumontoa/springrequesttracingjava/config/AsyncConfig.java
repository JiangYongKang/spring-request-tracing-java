package com.github.highcumontoa.springrequesttracingjava.config;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceTaskDecorator;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.scheduling.annotation.EnableAsync;
import org.springframework.scheduling.concurrent.ThreadPoolTaskExecutor;

import java.util.concurrent.Executor;

/**
 * Async executor wired with {@link TraceTaskDecorator} so {@code @Async} work
 * keeps the originating request's trace id.
 */
@Configuration
@EnableAsync
public class AsyncConfig {

    public static final String TASK_EXECUTOR = "traceTaskExecutor";

    @Bean(name = TASK_EXECUTOR)
    public Executor traceTaskExecutor() {
        ThreadPoolTaskExecutor executor = new ThreadPoolTaskExecutor();
        executor.setCorePoolSize(4);
        executor.setMaxPoolSize(16);
        executor.setQueueCapacity(200);
        executor.setThreadNamePrefix("trace-async-");
        executor.setTaskDecorator(new TraceTaskDecorator());
        executor.initialize();
        return executor;
    }
}
