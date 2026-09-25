package com.pos.events.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A stock adjustment was approved and posted - a write-off, a correction, or the differences a
 * stock take found (reason {@code STOCK_TAKE}, the id then being the stock take's).
 */
public record AdjustmentPostedPayload(
        UUID adjustmentId,
        UUID branchId,
        String reasonCode,
        UUID postedBy,
        Instant postedAt,
        String notes,
        List<AdjustmentLine> lines) {

    /**
     * @param quantityDelta signed: negative writes stock off, positive puts it on
     * @param valueAtCost signed like the quantity: what stock taken off was worth at the cost its
     *     batches came in at; zero for stock put on, which has no delivery behind it
     */
    public record AdjustmentLine(
            UUID productId,
            String sku,
            BigDecimal quantityDelta,
            String batchNumber,
            BigDecimal valueAtCost,
            String currency) {}
}
