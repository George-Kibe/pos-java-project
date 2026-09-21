package com.pos.purchasing.domain;

/** Whether a supplier may be traded with. */
public enum SupplierStatus {
    ACTIVE,

    /**
     * No new orders, but the history stays readable and outstanding deliveries can still be
     * received - a supplier put on hold mid-delivery has goods on a lorry already.
     */
    ON_HOLD,

    INACTIVE;

    public boolean canAcceptNewOrders() {
        return this == ACTIVE;
    }
}
