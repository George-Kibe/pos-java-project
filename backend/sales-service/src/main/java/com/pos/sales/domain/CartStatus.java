package com.pos.sales.domain;

/** A basket's life. */
public enum CartStatus {
    OPEN,

    /** Parked so the queue can be served; recalled by a short code on the printed ticket. */
    SUSPENDED,

    CHECKED_OUT,

    /** Given up on. Kept, because what customers abandon at the till is worth knowing. */
    ABANDONED;

    public boolean isEditable() {
        return this == OPEN;
    }
}
