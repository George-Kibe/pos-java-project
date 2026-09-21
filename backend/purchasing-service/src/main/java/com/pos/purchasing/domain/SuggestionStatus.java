package com.pos.purchasing.domain;

/** A reorder suggestion's life. */
public enum SuggestionStatus {
    OPEN,

    /** Turned into a purchase order line. */
    ORDERED,

    /** Rejected by a buyer, with a reason, and not proposed again. */
    DISMISSED
}
