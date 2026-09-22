package com.pos.events.customers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A member moved between tiers.
 *
 * <p>Both directions: rolling spend falls as old sales age out of the window, and a member who
 * stops shopping comes down again. {@code previousTierCode} is null only for a first placement.
 */
public record TierChangedPayload(
        UUID customerId,
        UUID accountId,
        String previousTierCode,
        String tierCode,
        BigDecimal rollingSpend,
        String currency,
        boolean upgrade,
        Instant changedAt) {}
