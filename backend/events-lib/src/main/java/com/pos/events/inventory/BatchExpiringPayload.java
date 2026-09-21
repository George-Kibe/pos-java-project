package com.pos.events.inventory;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A batch is close to its expiry date.
 *
 * <p>Sent early enough to do something about it - mark it down, move it to a busier branch - which
 * is the difference between a discount and a write-off.
 */
public record BatchExpiringPayload(
        UUID batchId,
        String batchNumber,
        UUID productId,
        String sku,
        String productName,
        UUID branchId,
        LocalDate expiryDate,
        long daysUntilExpiry,
        BigDecimal quantityRemaining,
        BigDecimal valueAtCost,
        String currency) {}
