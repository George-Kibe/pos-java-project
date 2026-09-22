package com.pos.events.inventory;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * What a branch's stock was worth at a moment, product by product.
 *
 * <p>Inventory is the only service that costs stock, so it says what the value is rather than
 * leaving another service to rebuild the ledger and disagree. A snapshot for a busy branch is too
 * large for one message, so it is sent in pages: {@code snapshotId} ties them together and {@code
 * pageCount} tells a consumer when it has the whole picture.
 *
 * @param valuedAt the instant the whole snapshot describes; every page carries the same one
 * @param lines products with stock on hand, or with stock that has gone negative
 */
public record StockValuedPayload(
        UUID snapshotId,
        UUID branchId,
        Instant valuedAt,
        int page,
        int pageCount,
        List<ValuedLine> lines) {

    /**
     * @param valueAtCost on-hand quantity at the landed cost of the batches holding it
     */
    public record ValuedLine(
            UUID productId,
            String sku,
            BigDecimal quantityOnHand,
            BigDecimal valueAtCost,
            String currency) {}
}
