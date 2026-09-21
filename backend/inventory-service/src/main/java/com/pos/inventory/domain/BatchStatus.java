package com.pos.inventory.domain;

/** Whether a batch can still be sold from. */
public enum BatchStatus {
    ACTIVE,
    /** Emptied by sales or transfers. Kept, because its movements still reference it. */
    DEPLETED,
    /** Destroyed or returned to the supplier. */
    WRITTEN_OFF
}
