package com.pos.sales.domain;

/**
 * Where a sale is.
 *
 * <pre>
 *   PENDING ──▶ AWAITING_PAYMENT ──▶ PAID ──▶ VOIDED
 *      │               │
 *      └───────────────┴──▶ CANCELLED
 * </pre>
 *
 * <p>PAID is the point of no return: the customer has the goods and a receipt, so the only way back
 * is a void or a refund - both new facts about the sale, never an edit of it.
 */
public enum SaleStatus {
    /** Being built, or created offline and not yet settled. */
    PENDING,

    /** A payment has been requested and the answer has not arrived. */
    AWAITING_PAYMENT,

    PAID,

    /** Abandoned before money changed hands. Stock reservations are released. */
    CANCELLED,

    /** Reversed after payment, with a supervisor's approval. The original stays. */
    VOIDED;

    public boolean canTransitionTo(SaleStatus next) {
        return switch (this) {
            case PENDING -> next == AWAITING_PAYMENT || next == PAID || next == CANCELLED;
            case AWAITING_PAYMENT -> next == PAID || next == CANCELLED;
            case PAID -> next == VOIDED;
            case CANCELLED, VOIDED -> false;
        };
    }

    public boolean isSettled() {
        return this == PAID || this == VOIDED;
    }

    /** Whether stock should be considered sold. A voided sale's stock goes back. */
    public boolean countsAsSold() {
        return this == PAID;
    }
}
