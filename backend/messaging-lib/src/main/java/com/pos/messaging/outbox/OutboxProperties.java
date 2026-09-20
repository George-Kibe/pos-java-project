package com.pos.messaging.outbox;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/** Tuning for the outbox relay. Defaults suit a lane that must feel instant. */
@ConfigurationProperties(prefix = "pos.outbox")
public class OutboxProperties {

    /** Turn the relay off in a service that only consumes events. */
    private boolean enabled = true;

    /** How often the relay looks for due rows. */
    private Duration pollInterval = Duration.ofSeconds(1);

    /** Rows claimed per pass. Bounded so one slow broker cannot hold a long transaction. */
    private int batchSize = 100;

    /** How long to wait for the broker to acknowledge one record. */
    private Duration sendTimeout = Duration.ofSeconds(10);

    /** After this many failures a row is parked as FAILED and must be dealt with by a human. */
    private int maxAttempts = 10;

    /** Ceiling on the exponential backoff between attempts. */
    private Duration maxBackoff = Duration.ofMinutes(5);

    public boolean isEnabled() {
        return enabled;
    }

    public void setEnabled(boolean enabled) {
        this.enabled = enabled;
    }

    public Duration getPollInterval() {
        return pollInterval;
    }

    public void setPollInterval(Duration pollInterval) {
        this.pollInterval = pollInterval;
    }

    public int getBatchSize() {
        return batchSize;
    }

    public void setBatchSize(int batchSize) {
        this.batchSize = batchSize;
    }

    public Duration getSendTimeout() {
        return sendTimeout;
    }

    public void setSendTimeout(Duration sendTimeout) {
        this.sendTimeout = sendTimeout;
    }

    public int getMaxAttempts() {
        return maxAttempts;
    }

    public void setMaxAttempts(int maxAttempts) {
        this.maxAttempts = maxAttempts;
    }

    public Duration getMaxBackoff() {
        return maxBackoff;
    }

    public void setMaxBackoff(Duration maxBackoff) {
        this.maxBackoff = maxBackoff;
    }
}
