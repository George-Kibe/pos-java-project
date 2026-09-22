package com.pos.events.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A completed sale cancelled before the customer left.
 *
 * <p>A void is a new fact about an old sale, never an erasure: the original stays, and this event
 * is what tells inventory to put the stock back and reporting to take the takings out.
 *
 * @param approvedBy the supervisor who authorised it, which is the whole point of recording it
 */
public record SaleVoidedPayload(
        UUID saleId,
        String receiptNumber,
        UUID branchId,
        UUID registerId,
        UUID cashierId,
        UUID approvedBy,
        String reasonCode,
        String notes,
        BigDecimal grandTotal,
        String currency,
        Instant voidedAt) {}
