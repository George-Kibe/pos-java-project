package com.pos.common.security;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Per-service security configuration. Defaults are deny-by-default and stateless. */
@ConfigurationProperties(prefix = "pos.security")
public class PosSecurityProperties {

    /** Set false only in a service that configures its own filter chain, such as auth-service. */
    private boolean enabled = true;

    /**
     * Paths reachable without authentication. Everything not listed requires a valid token. Keep
     * this list short and review every addition - each entry is an unauthenticated surface.
     */
    private List<String> publicPaths =
            new ArrayList<>(
                    List.of(
                            "/actuator/health",
                            "/actuator/health/**",
                            "/actuator/info",
                            "/actuator/prometheus",
                            "/v3/api-docs/**",
                            "/swagger-ui/**",
                            "/swagger-ui.html"));

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getPublicPaths() {
        return publicPaths;
    }

    public void setPublicPaths(List<String> publicPaths) {
        this.publicPaths = publicPaths;
    }
}
