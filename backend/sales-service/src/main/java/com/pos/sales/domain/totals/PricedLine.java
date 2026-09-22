package com.pos.sales.domain.totals;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line as catalog priced it.
 *
 * <p>Every money figure here came back from catalog's pricing engine and is carried through
 * unchanged. sales-service does not recompute tax: two implementations of one rule drift, and the
 * receipt would then disagree with the price the shelf edge promised.
 */
public record PricedLine(
        UUID productId,
        String sku,
        BigDecimal quantity,
        String taxClassCode,
        BigDecimal taxRate,
        BigDecimal netAmount,
        BigDecimal taxAmount,
        BigDecimal discountTotal,
        BigDecimal lineTotal) {}
