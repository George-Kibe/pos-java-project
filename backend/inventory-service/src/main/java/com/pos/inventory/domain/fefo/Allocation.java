package com.pos.inventory.domain.fefo;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * How much of one line came out of one batch.
 *
 * <p>The unit cost travels with it because the cost of a sale is the cost of the specific batches
 * it drew from, and that is what a margin report needs.
 */
public record Allocation(
        UUID batchId,
        String batchNumber,
        BigDecimal quantity,
        BigDecimal unitCost,
        String currency) {}
