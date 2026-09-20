package com.pos.catalog.domain.barcode;

import java.math.BigDecimal;

/**
 * What a scale barcode turned out to mean.
 *
 * <p>Exactly one of {@code weight} and {@code embeddedPrice} is set, according to the rule that
 * matched. A price-embedded barcode already states what to charge for that exact piece of meat, so
 * the till uses it directly rather than recomputing from a per-kilogram price - recomputing would
 * disagree with the label stuck on the package.
 */
public record ScaleBarcodeScan(
        String barcode,
        String ruleName,
        String itemCode,
        BigDecimal weight,
        BigDecimal embeddedPrice) {

    public boolean carriesWeight() {
        return weight != null;
    }

    public boolean carriesPrice() {
        return embeddedPrice != null;
    }
}
