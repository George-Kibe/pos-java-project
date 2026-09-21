package com.pos.inventory.domain;

/**
 * Why an adjustment was made.
 *
 * <p>A fixed list rather than free text: shrinkage is only analysable if the reasons are comparable
 * across branches and months, and "damaged?" and "broken" would be two different categories
 * forever.
 */
public enum AdjustmentReason {
    DAMAGE,
    EXPIRY,
    THEFT,
    /** The count was right and the system was wrong. */
    COUNT_CORRECTION,
    SUPPLIER_RETURN,
    /** Used in-store: tastings, staff samples, cleaning products consumed on site. */
    SAMPLE,
    OTHER
}
