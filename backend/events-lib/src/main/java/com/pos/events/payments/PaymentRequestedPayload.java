package com.pos.events.payments;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * Sales is asking for money.
 *
 * <p>One event per tender, not per sale: a basket split across cash and M-Pesa produces two
 * requests, and each is authorised or refused on its own.
 *
 * @param paymentIntentId assigned by sales, so a duplicate request is recognisable
 * @param phoneNumber for M-Pesa only, and never logged in full
 * @param terminalReference for a card terminal only; the approval code arrives on authorisation
 */
public record PaymentRequestedPayload(
        UUID paymentIntentId,
        UUID saleId,
        String receiptNumber,
        UUID branchId,
        UUID registerId,
        UUID cashierId,
        PaymentMethod method,
        BigDecimal amount,
        String currency,
        String phoneNumber,
        String terminalReference,
        Instant requestedAt) {}
