package com.pos.catalog.domain.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.pos.common.money.Money;

/**
 * Everything about one priced line, in the order it is worked out.
 *
 * <p>This is the contract between catalog and the till. sales-service snapshots these values onto
 * the sale line, and the receipt prints them, so the number shown on the shelf edge, the number the
 * till charges and the number on the VAT return are all the same number, arrived at once.
 *
 * <p>Two invariants hold for every breakdown, and both are tested:
 *
 * <ul>
 *   <li>{@code discountedSubtotal == subtotal - discountTotal}
 *   <li>{@code net + tax == lineTotal}
 * </ul>
 */
public record PriceBreakdown(
        UUID productId,
        String sku,
        String productName,
        BigDecimal quantity,

        /** The unit price as listed - inclusive or exclusive according to the product. */
        Money unitPrice,
        PriceSource priceSource,
        UUID priceListId,
        boolean taxInclusive,

        /** unitPrice x quantity, before any discount. */
        Money subtotal,
        List<AppliedDiscount> discounts,
        Money discountTotal,
        Money discountedSubtotal,
        String taxClassCode,
        BigDecimal taxRate,
        Money net,
        Money tax,

        /** What the customer pays for this line. Always net + tax. */
        Money lineTotal) {

    public boolean hasDiscounts() {
        return !discounts.isEmpty();
    }
}
