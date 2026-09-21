package com.pos.events.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * Stock arrived from a supplier.
 *
 * <p>Each line carries its batch and expiry because that is the only moment the information exists:
 * it is read off the carton at the goods-in door. Without it there is no way to deduct oldest-first
 * later, and no way to find what is about to expire.
 */
public record GoodsReceivedPayload(
        UUID goodsReceivedNoteId,
        UUID purchaseOrderId,
        UUID branchId,
        UUID supplierId,
        UUID receivedBy,
        Instant receivedAt,
        List<ReceivedLine> lines) {

    /**
     * @param expiryDate a date, not an instant: a carton is stamped "best before 12 May", which is
     *     a calendar day rather than a moment
     * @param unitCost what this delivery cost, which is what stock is valued at
     */
    public record ReceivedLine(
            UUID productId,
            String sku,
            BigDecimal quantity,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal unitCost,
            String currency) {}
}
