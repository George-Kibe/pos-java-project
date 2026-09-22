package com.pos.sales.config;

import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Where the services sales depends on live, and how long it will wait for them.
 *
 * @param returnWindowDays the shop's returns policy; beyond it a return needs a supervisor
 * @param paymentTimeout how long a sale waits for an answer before the saga compensates
 */
@ConfigurationProperties(prefix = "pos.sales")
public record ServiceEndpointProperties(
        String catalogUri,
        String inventoryUri,
        Duration pricingTimeout,
        Duration reservationTimeout,
        Duration paymentTimeout,
        String paymentSweepCron,
        int returnWindowDays,
        String receiptPrefix) {

    public Duration pricingTimeoutOrDefault() {
        return pricingTimeout == null ? Duration.ofSeconds(3) : pricingTimeout;
    }

    public Duration reservationTimeoutOrDefault() {
        return reservationTimeout == null ? Duration.ofSeconds(2) : reservationTimeout;
    }

    public Duration paymentTimeoutOrDefault() {
        return paymentTimeout == null ? Duration.ofMinutes(2) : paymentTimeout;
    }

    public int returnWindowDaysOrDefault() {
        return returnWindowDays > 0 ? returnWindowDays : 30;
    }

    public String receiptPrefixOrDefault() {
        return receiptPrefix == null || receiptPrefix.isBlank() ? "R" : receiptPrefix;
    }
}
