package com.pos.messaging.idempotency;

import java.util.ArrayList;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

@ConfigurationProperties(prefix = "pos.idempotency")
public class IdempotencyProperties {

    /**
     * Honour {@code Idempotency-Key} on mutating requests. Off by default: a service turning it on
     * must also create the {@code idempotency_records} table in its own migration.
     */
    private boolean enabled = false;

    /** Path prefixes that keep an idempotency of their own and must not be replayed from here. */
    private List<String> excludedPaths = new ArrayList<>();

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public List<String> getExcludedPaths() {
        return excludedPaths;
    }

    public void setExcludedPaths(List<String> excludedPaths) {
        this.excludedPaths = excludedPaths;
    }
}
