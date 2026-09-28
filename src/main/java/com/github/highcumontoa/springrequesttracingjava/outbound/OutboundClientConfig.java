package com.github.highcumontoa.springrequesttracingjava.outbound;

import org.springframework.boot.web.client.RestClientCustomizer;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;

/**
 * Registers the trace interceptor on the auto-configured
 * {@link org.springframework.web.client.RestClient.Builder}, so every RestClient
 * built from it (including the one used by demo code and production callers)
 * propagates the trace id without per-call-site configuration.
 */
@Configuration
public class OutboundClientConfig {

    @Bean
    public RestClientCustomizer tracingRestClientCustomizer(
            TraceClientHttpRequestInterceptor interceptor) {
        return restClientBuilder -> restClientBuilder.requestInterceptor(interceptor);
    }
}
