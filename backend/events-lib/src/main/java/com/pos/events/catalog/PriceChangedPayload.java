package com.pos.events.catalog;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * A product's price changed.
 *
 * <p>Separate from {@code product-changed} because a price change matters to services that do not
 * care that a product was renamed, and because "why is yesterday's receipt a different figure" is a
 * question somebody eventually asks. Both the old and the new price are carried so the answer does
 * not require reconstructing history.
 *
 * @param branchId the branch whose price list changed, or null for the base price
 */
public record PriceChangedPayload(
        UUID productId,
        String sku,
        UUID branchId,
        BigDecimal previousPrice,
        BigDecimal newPrice,
        String currency,
        boolean priceIncludesTax) {}
