package com.pos.events.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * The money is there.
 *
 * <p>Carries {@code providerReference} because that is what a dispute is settled with - an M-Pesa
 * receipt number or a card approval code. A sale marked paid with no provider reference cannot be
 * defended later.
 *
 * @param amountAuthorized may be less than requested: a partial M-Pesa payment is possible
 */
public record PaymentAuthorizedPayload(
        UUID paymentIntentId,
        UUID saleId,
        UUID branchId,
        PaymentMethod method,
        BigDecimal amountAuthorized,
        String currency,
        String providerReference,
        String approvalCode,
        Instant authorizedAt) {}
