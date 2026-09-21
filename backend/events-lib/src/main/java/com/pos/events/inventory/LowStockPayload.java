package com.pos.events.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/** A product has fallen to or below its reorder point at a branch. */
public record LowStockPayload(
        UUID productId,
        String sku,
        String productName,
        UUID branchId,
        BigDecimal quantityOnHand,
        BigDecimal reorderPoint,
        BigDecimal suggestedOrderQuantity) {}
