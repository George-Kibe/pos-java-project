package com.pos.catalog.service;

import java.math.BigDecimal;

import com.pos.catalog.domain.pricing.PriceBreakdown;

/**
 * What the till gets back when a barcode is scanned.
 *
 * <p>A scale barcode carries its own quantity - and sometimes its own price - so the till must not
 * assume one unit. {@code quantityFromBarcode} tells it whether the quantity was decided by the
 * label or needs asking for.
 */
public record ScanResult(
        String barcode,
        boolean scaleBarcode,
        String scaleRuleName,
        BigDecimal quantityFromBarcode,
        BigDecimal priceFromBarcode,
        PriceBreakdown price) {}
