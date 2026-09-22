package com.pos.sales.client;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

/**
 * Catalog's answer for one line.
 *
 * <p>Mirrors catalog's {@code PriceResponse} rather than importing it: a service never depends on
 * another service, so the wire shape is declared here. The field names must match catalog's, which
 * is what {@code CatalogPricingClientIT} checks.
 */
public record PricedLineResponse(
        UUID productId,
        String sku,
        String productName,
        BigDecimal quantity,
        BigDecimal unitPrice,
        String priceSource,
        UUID priceListId,
        Boolean taxInclusive,
        BigDecimal subtotal,
        List<AppliedDiscount> discounts,
        BigDecimal discountTotal,
        BigDecimal discountedSubtotal,
        String taxClassCode,
        BigDecimal taxRate,
        BigDecimal net,
        BigDecimal tax,
        BigDecimal lineTotal,
        String currency) {

    public record AppliedDiscount(
            UUID promotionId, String code, String name, String type, BigDecimal amount) {}

    public boolean isTaxInclusive() {
        return !Boolean.FALSE.equals(taxInclusive);
    }
}
