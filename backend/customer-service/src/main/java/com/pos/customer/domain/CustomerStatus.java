package com.pos.customer.domain;

public enum CustomerStatus {
    ACTIVE,
    /** Kept, not deleted: their history is still part of the shop's records. */
    INACTIVE,
    /** Personal details removed on request; the ledger remains. */
    ERASED
}
