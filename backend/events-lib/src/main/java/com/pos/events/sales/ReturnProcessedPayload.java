package com.pos.events.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pos.events.payments.PaymentMethod;

/**
 * Goods came back.
 *
 * <p>{@code resaleable} decides whether inventory puts the item back on the shelf or writes it off.
 * Returning a damaged item to sellable stock is how a shop ends up selling something it already
 * knows is broken, so the till must state it and inventory must honour it.
 */
public record ReturnProcessedPayload(
        UUID returnId,
        UUID originalSaleId,
        UUID branchId,
        UUID cashierId,
        Instant processedAt,
        List<ReturnLine> lines,
        BigDecimal refundTotal,
        String currency,
        PaymentMethod refundMethod) {

    public record ReturnLine(
            UUID productId,
            String sku,
            BigDecimal quantity,
            boolean resaleable,
            String batchNumber,
            String reason) {}
}
