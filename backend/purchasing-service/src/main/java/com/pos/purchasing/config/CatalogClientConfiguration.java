package com.pos.purchasing.config;

import java.time.Duration;

import org.springframework.boot.http.client.ClientHttpRequestFactoryBuilder;
import org.springframework.boot.http.client.HttpClientSettings;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.web.client.RestClient;

/** The HTTP client for catalog, with timeouts short enough for the circuit breaker to matter. */
@Configuration
public class CatalogClientConfiguration {

    @Bean
    public RestClient catalogRestClient(
            RestClient.Builder builder, PurchasingProperties properties) {
        return builder.baseUrl(properties.catalogUri())
                .requestFactory(
                        ClientHttpRequestFactoryBuilder.detect()
                                .build(
                                        HttpClientSettings.defaults()
                                                .withTimeouts(
                                                        Duration.ofSeconds(2),
                                                        properties.catalogTimeoutOrDefault())))
                .build();
    }
}
