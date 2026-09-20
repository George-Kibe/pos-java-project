package com.pos.catalog.domain.pricing;

/** Where a unit price came from. Shown in the breakdown so a price can always be accounted for. */
public enum PriceSource {
    /** The product's own price. Always available, so pricing never fails for want of a rule. */
    BASE_PRICE,
    /** A branch price list overrode the base price. */
    PRICE_LIST,
    /** The price came off a scale barcode, which carries its own price for that exact weight. */
    SCALE_BARCODE
}
