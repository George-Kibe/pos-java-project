package com.pos.events.customers;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A member earned points on a sale.
 *
 * <p>Carries the balance after, so a consumer (a receipt footer, a notification) can state it
 * without asking - and the spend the points were earned on, because a later claw-back on a refund
 * has to be proportional to it.
 *
 * @param points what this sale earned; never negative - a claw-back is its own event
 * @param expiresAt when these points lapse if unspent
 */
public record LoyaltyAccruedPayload(
        UUID customerId,
        UUID accountId,
        UUID saleId,
        UUID branchId,
        long points,
        long balanceAfter,
        BigDecimal eligibleSpend,
        String currency,
        String tierCode,
        Instant expiresAt,
        Instant accruedAt) {}
