package com.pos.purchasing.domain;

/** A return's life, ending in a credit note from the supplier. */
public enum SupplierReturnStatus {
    DRAFT,
    SENT,
    CREDITED,
    CANCELLED;

    public boolean canTransitionTo(SupplierReturnStatus next) {
        return switch (this) {
            case DRAFT -> next == SENT || next == CANCELLED;
            case SENT -> next == CREDITED || next == CANCELLED;
            case CREDITED, CANCELLED -> false;
        };
    }
}
