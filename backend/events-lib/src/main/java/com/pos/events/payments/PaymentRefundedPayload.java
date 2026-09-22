package com.pos.events.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Money went back to a customer through the provider it came in by.
 *
 * <p>Cash refunds never appear here: they leave the drawer at the till and sales accounts for them.
 * This is the card or M-Pesa leg, emitted once the provider has actually confirmed it - a reversal
 * that was requested but not yet confirmed has not refunded anyone.
 *
 * @param providerReference the reversal's M-Pesa transaction id, or the card terminal's refund
 *     reference; what a dispute is settled with
 */
public record PaymentRefundedPayload(
        UUID refundId,
        UUID paymentIntentId,
        UUID saleId,
        UUID returnId,
        UUID branchId,
        PaymentMethod method,
        BigDecimal amount,
        String currency,
        String providerReference,
        Instant refundedAt) {}
