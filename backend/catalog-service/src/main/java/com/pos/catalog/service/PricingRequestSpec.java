package com.pos.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * What the caller asks to be priced.
 *
 * <p>{@code at} is explicit rather than "now" so that a reprint, a return or a queued offline sale
 * can be priced with the rates and prices that were in force when it happened. Defaulting to now
 * would silently reprice history.
 */
public record PricingRequestSpec(
        UUID productId,
        String sku,
        String barcode,
        BigDecimal quantity,
        UUID branchId,
        boolean member,
        Instant at) {

    public Instant effectiveAt() {
        return at == null ? Instant.now() : at;
    }
}
