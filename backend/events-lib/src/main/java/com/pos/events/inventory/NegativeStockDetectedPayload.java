package com.pos.events.inventory;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * On-hand stock went below zero.
 *
 * <p>Always a symptom of something else: a delivery received late, a sale synced from a till that
 * was offline, a count that was wrong. The sale is never blocked - the customer has already walked
 * out with the goods - but somebody needs to know, because every later figure for this product is
 * now suspect.
 */
public record NegativeStockDetectedPayload(
        UUID productId,
        String sku,
        UUID branchId,
        BigDecimal quantityOnHand,
        BigDecimal attemptedDeduction,
        String triggeredBy,
        UUID referenceId) {}
