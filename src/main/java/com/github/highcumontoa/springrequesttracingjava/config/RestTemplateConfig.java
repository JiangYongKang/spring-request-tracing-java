package com.github.highcumontoa.springrequesttracingjava.config;

import com.github.highcumontoa.springrequesttracingjava.tracing.TraceClientHttpRequestInterceptor;
import org.springframework.boot.web.client.RestTemplateBuilder;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestTemplate;

import java.time.Duration;

/**
 * RestTemplate that propagates the trace id on outbound calls and uses bounded
 * timeouts so a hanging downstream never stalls a request thread forever.
 */
@Configuration
public class RestTemplateConfig {

    @Bean
    public RestTemplate restTemplate(RestTemplateBuilder builder) {
        return builder
                .connectTimeout(Duration.ofSeconds(2))
                .readTimeout(Duration.ofSeconds(5))
                .interceptors(new TraceClientHttpRequestInterceptor())
                .build();
    }
}
