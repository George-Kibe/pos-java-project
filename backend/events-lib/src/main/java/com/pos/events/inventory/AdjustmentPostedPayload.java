package com.pos.events.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/** A stock adjustment was approved and posted. */
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
     */
    public record AdjustmentLine(
            UUID productId,
            String sku,
            BigDecimal quantityDelta,
            String batchNumber,
            BigDecimal valueAtCost,
            String currency) {}
}
