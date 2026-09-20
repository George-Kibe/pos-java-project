package com.pos.gateway.config;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * CORS policy.
 *
 * <p>An explicit allowlist, never a wildcard. Credentials are carried on these requests, and {@code
 * Access-Control-Allow-Origin: *} cannot be combined with credentials anyway - browsers refuse it.
 * Each environment lists the origins it actually serves.
 */
@ConfigurationProperties(prefix = "pos.gateway.cors")
@Getter
@Setter
public class GatewayCorsProperties {

    private List<String> allowedOrigins = new ArrayList<>(List.of("http://localhost:3000"));

    private List<String> allowedMethods =
            new ArrayList<>(List.of("GET", "POST", "PUT", "PATCH", "DELETE", "OPTIONS"));

    private List<String> allowedHeaders =
            new ArrayList<>(
                    List.of(
                            "Authorization",
                            "Content-Type",
                            "X-Correlation-Id",
                            "Idempotency-Key",
                            "Accept"));

    /** Headers a browser client is allowed to read off the response. */
    private List<String> exposedHeaders =
            new ArrayList<>(
                    List.of(
                            "X-Correlation-Id",
                            "Retry-After",
                            "X-RateLimit-Limit",
                            "X-RateLimit-Remaining",
                            "Location"));

    private boolean allowCredentials = true;

    private long maxAgeSeconds = 3600;
}
