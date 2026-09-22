package com.pos.gateway.config;

import java.time.Duration;
import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/**
 * Rate limit policy.
 *
 * <p>Three buckets rather than one, because the traffic is not alike. Credential endpoints are
 * keyed by IP and kept deliberately tight: they are the ones worth brute forcing, and a cashier
 * signs in a handful of times a day. Authenticated traffic is keyed by user and generous, because a
 * busy lane legitimately makes a lot of requests and throttling it would stop the business trading.
 * Everything else is keyed by IP in between.
 */
@ConfigurationProperties(prefix = "pos.gateway.rate-limit")
@Getter
@Setter
public class GatewayRateLimitProperties {

    private boolean enabled = true;

    /** Credential endpoints, keyed by client IP. */
    private Bucket auth = new Bucket(10, Duration.ofMinutes(1));

    /** Authenticated traffic, keyed by user id. */
    private Bucket authenticated = new Bucket(300, Duration.ofMinutes(1));

    /** Unauthenticated traffic that is not a credential endpoint, keyed by client IP. */
    private Bucket anonymous = new Bucket(60, Duration.ofMinutes(1));

    /**
     * Payment provider callbacks, keyed by client IP. A provider calls from a handful of addresses
     * on behalf of every customer at once, so the anonymous limit would throttle a busy shortcode -
     * and a callback refused with 429 is a payment the lane has to wait on the status query for.
     */
    private Bucket providerCallbacks = new Bucket(1200, Duration.ofMinutes(1));

    /** Where providers call back. Explicit, like {@link #authPaths}. */
    private List<String> providerCallbackPaths =
            new ArrayList<>(List.of("/api/v1/payments/mpesa/callbacks/**"));

    /**
     * The credential endpoints. Kept explicit rather than pattern-matched on {@code /auth/}: a path
     * added here should be a decision, not a side effect of a naming choice.
     */
    private List<String> authPaths =
            new ArrayList<>(
                    List.of(
                            "/api/v1/auth/login",
                            "/api/v1/auth/register",
                            "/api/v1/auth/verify-otp",
                            "/api/v1/auth/resend-otp",
                            "/api/v1/auth/refresh",
                            "/api/v1/auth/forgot-password",
                            "/api/v1/auth/reset-password"));

    @Getter
    @Setter
    public static class Bucket {
        /** Burst size, and the number of requests allowed per window once refilled. */
        private int capacity;

        private Duration window;

        public Bucket() {}

        public Bucket(int capacity, Duration window) {
            this.capacity = capacity;
            this.window = window;
        }
    }
}
