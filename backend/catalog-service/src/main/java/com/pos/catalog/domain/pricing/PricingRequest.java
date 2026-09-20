package com.pos.catalog.domain.pricing;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import com.pos.common.money.Money;

/** Everything needed to price one line, already resolved. */
public record PricingRequest(
        UUID productId,
        String sku,
        String productName,
        BigDecimal quantity,
        Money unitPrice,
        PriceSource priceSource,
        UUID priceListId,
        boolean taxInclusive,
        String taxClassCode,
        BigDecimal taxRate,
        List<PromotionCandidate> promotions) {}
