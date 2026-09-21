package com.pos.events.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * Stock was taken off the shelf for a sale.
 *
 * <p>Reports which batches were consumed, because the cost of a sale is the cost of the specific
 * batches it drew from. Reporting needs that to state a margin; an averaged cost would be a guess.
 */
public record StockDeductedPayload(
        UUID saleId, UUID branchId, Instant deductedAt, List<DeductedLine> lines) {

    public record DeductedLine(
            UUID productId,
            BigDecimal quantity,
            BigDecimal quantityOnHandAfter,
            List<BatchAllocation> allocations) {}

    /** One batch and how much of this line came out of it. */
    public record BatchAllocation(
            UUID batchId, String batchNumber, BigDecimal quantity, BigDecimal unitCost) {}
}
