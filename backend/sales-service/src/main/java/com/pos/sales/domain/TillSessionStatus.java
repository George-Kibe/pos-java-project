package com.pos.sales.domain;

/** A shift's life. */
public enum TillSessionStatus {
    OPEN,

    /** The count is being keyed in. No new sales, so the figure cannot move underneath it. */
    CLOSING,

    CLOSED;

    public boolean acceptsSales() {
        return this == OPEN;
    }
}
