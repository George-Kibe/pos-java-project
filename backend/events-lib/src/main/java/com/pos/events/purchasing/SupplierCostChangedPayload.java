package com.pos.events.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What a product costs from a supplier has changed.
 *
 * <p>Emitted when a delivery arrives at a different price from the agreed one, which is how cost
 * changes actually surface in a shop - not by a supplier announcing them. Catalog and reporting
 * care: a cost that has risen above the shelf price is selling at a loss on every scan.
 *
 * @param previousUnitCost null the first time a product is bought from this supplier
 */
public record SupplierCostChangedPayload(
        UUID supplierId,
        String supplierName,
        UUID productId,
        String sku,
        BigDecimal previousUnitCost,
        BigDecimal newUnitCost,
        String currency,
        String sourceType,
        UUID sourceId,
        Instant changedAt) {

    /** Positive when the cost went up. Null when there is nothing to compare against. */
    public BigDecimal delta() {
        return previousUnitCost == null ? null : newUnitCost.subtract(previousUnitCost);
    }
}
