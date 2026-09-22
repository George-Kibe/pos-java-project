package com.pos.sales.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/**
 * The HTTP clients sales uses to reach catalog and inventory.
 *
 * <p>Timeouts are short on purpose. A checkout lane has a customer standing at it, so a dependency
 * that is merely slow has to be treated as absent quickly - the circuit breaker around each call
 * needs something to trip on.
 *
 * <p>No interceptor propagates the correlation id, deliberately. The call runs on the circuit
 * breaker's own thread pool, where the MDC is empty, so an interceptor would find nothing and the
 * id would silently stop at this service. Each client captures it on the calling thread instead.
 */
@Configuration(proxyBeanMethods = false)
public class ServiceClientConfiguration {

    @Bean
    public RestClient catalogRestClient(
            RestClient.Builder builder, ServiceEndpointProperties endpoints) {
        return client(builder, endpoints.catalogUri(), endpoints.pricingTimeoutOrDefault());
    }

    @Bean
    public RestClient inventoryRestClient(
            RestClient.Builder builder, ServiceEndpointProperties endpoints) {
        return client(builder, endpoints.inventoryUri(), endpoints.reservationTimeoutOrDefault());
    }

    private static RestClient client(RestClient.Builder builder, String baseUrl, Duration timeout) {
        // Boot 4 replaced ClientHttpRequestFactorySettings with HttpClientSettings, applied
        // through a request factory built for whichever HTTP client is on the classpath.
        return builder.baseUrl(baseUrl)
                .requestFactory(
                        ClientHttpRequestFactoryBuilder.detect()
                                .build(
                                        HttpClientSettings.defaults()
                                                .withTimeouts(Duration.ofSeconds(2), timeout)))
                .build();
    }
}
