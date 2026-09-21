package com.pos.purchasing.domain;

/** Where a supplier invoice stands. */
public enum InvoiceMatchStatus {
    /** Received, not yet matched. */
    PENDING,

    MATCHED,
    WITHIN_TOLERANCE,

    /** Beyond tolerance. Needs a person. */
    EXCEPTION,

    /** An exception taken up with the supplier. Excluded from payment runs. */
    DISPUTED,

    /**
     * Cleared to pay. Reachable from a clean match, or from an exception that someone accepted with
     * a recorded reason.
     */
    APPROVED_FOR_PAYMENT;

    public boolean isPayable() {
        return this == MATCHED || this == WITHIN_TOLERANCE || this == APPROVED_FOR_PAYMENT;
    }
}
