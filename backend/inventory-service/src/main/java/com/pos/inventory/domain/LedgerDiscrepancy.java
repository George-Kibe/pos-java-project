package com.pos.inventory.domain;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A stock item whose cached quantity disagrees with the sum of its movements.
 *
 * <p>The ledger is the source of truth and {@code quantity_on_hand} is a cache of it, so a
 * discrepancy means the cache drifted and the number on screen cannot be trusted. Always expected
 * to be empty.
 */
public record LedgerDiscrepancy(
        UUID stockItemId,
        UUID productId,
        UUID branchId,
        BigDecimal cachedQuantity,
        BigDecimal ledgerQuantity) {

    /** Positive when the cache claims more than the movements account for. */
    public BigDecimal drift() {
        return cachedQuantity.subtract(ledgerQuantity);
    }
}
