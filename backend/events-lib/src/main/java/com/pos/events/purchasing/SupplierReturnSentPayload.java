package com.pos.events.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Goods left a branch, going back to their supplier.
 *
 * <p>Sent when the return is dispatched, not when it is drafted: until then nothing has left the
 * shelf. Each line names the batch it came from where the branch knows it, so the stock goes back
 * out of the batch it arrived in rather than whichever expires first.
 */
public record SupplierReturnSentPayload(
        UUID returnId,
        String returnNumber,
        UUID supplierId,
        UUID branchId,
        String reasonCode,
        Instant sentAt,
        List<ReturnedLine> lines) {

    /**
     * @param unitCost the landed cost the goods came in at, which is what is credited
     */
    public record ReturnedLine(
            UUID productId,
            String sku,
            String batchNumber,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currency) {}
}
