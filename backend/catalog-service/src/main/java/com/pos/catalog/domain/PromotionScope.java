package com.pos.catalog.domain;

/** What a promotion rule points at. */
public enum PromotionScope {
    PRODUCT,
    CATEGORY,
    /** Everything. Used for store-wide events. */
    ALL
}
