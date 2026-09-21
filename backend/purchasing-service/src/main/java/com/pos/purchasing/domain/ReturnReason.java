package com.pos.purchasing.domain;

/** Why goods are going back to the supplier. */
public enum ReturnReason {
    DAMAGED_IN_TRANSIT,
    SHORT_DATED,
    EXPIRED,
    WRONG_ITEM,
    OVER_DELIVERY,
    QUALITY,
    RECALL,
    OTHER
}
