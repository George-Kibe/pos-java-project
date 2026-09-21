package com.pos.purchasing.domain.matching;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One product as it appears on an order, a receipt or an invoice.
 *
 * <p>The same shape serves all three so the matcher compares like with like.
 */
public record MatchableLine(UUID productId, String sku, BigDecimal quantity, BigDecimal unitCost) {

    public BigDecimal lineTotal() {
        return quantity.multiply(unitCost);
    }
}
