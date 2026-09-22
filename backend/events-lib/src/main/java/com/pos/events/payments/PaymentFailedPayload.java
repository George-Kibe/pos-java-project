package com.pos.events.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * No money.
 *
 * <p>A timeout is a failure here, deliberately: a till cannot hold a lane open waiting for a
 * customer who has walked away, and a late authorisation for a cancelled sale is detected and sent
 * to reconciliation rather than silently applied.
 *
 * @param reasonCode a stable code a cashier's screen can translate, e.g. INSUFFICIENT_FUNDS,
 *     CANCELLED_BY_USER, TIMEOUT, PROVIDER_UNAVAILABLE
 * @param providerMessage the provider's own words, for an operator - not for the customer
 */
public record PaymentFailedPayload(
        UUID paymentIntentId,
        UUID saleId,
        UUID branchId,
        PaymentMethod method,
        BigDecimal amountRequested,
        String currency,
        String reasonCode,
        String providerMessage,
        Instant failedAt) {}
