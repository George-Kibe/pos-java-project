package com.pos.purchasing.domain;

import java.util.EnumSet;
import java.util.Set;

/**
 * Where a purchase order is in its life, and where it may go next.
 *
 * <pre>
 *   DRAFT ──▶ SUBMITTED ──▶ APPROVED ──▶ SENT ──▶ PARTIALLY_RECEIVED ──▶ RECEIVED ──▶ CLOSED
 *     │           │             │          │               │
 *     └───────────┴─────────────┴──────────┴───────────────┴──▶ CANCELLED
 * </pre>
 *
 * <p>The transitions are declared here rather than checked ad hoc in a service, because the illegal
 * ones matter: an order that can go back from APPROVED to DRAFT is an order whose lines can be
 * edited after someone authorised the spend. Cancellation stays open until goods arrive - once
 * stock is on the shelf the order is part of history and is closed, not erased.
 */
public enum PurchaseOrderStatus {
    DRAFT,
    SUBMITTED,
    APPROVED,
    SENT,
    PARTIALLY_RECEIVED,
    RECEIVED,
    CLOSED,
    CANCELLED;

    private static final Set<PurchaseOrderStatus> RECEIVING = EnumSet.of(SENT, PARTIALLY_RECEIVED);

    public boolean canTransitionTo(PurchaseOrderStatus next) {
        return switch (this) {
            case DRAFT -> next == SUBMITTED || next == CANCELLED;
            case SUBMITTED -> next == APPROVED || next == DRAFT || next == CANCELLED;
            case APPROVED -> next == SENT || next == CANCELLED;
            case SENT -> next == PARTIALLY_RECEIVED || next == RECEIVED || next == CANCELLED;
            case PARTIALLY_RECEIVED ->
                    next == PARTIALLY_RECEIVED || next == RECEIVED || next == CANCELLED;
            case RECEIVED -> next == CLOSED;
            case CLOSED, CANCELLED -> false;
        };
    }

    /** A rejected submission goes back to the buyer, which is the only backwards step allowed. */
    public boolean isEditable() {
        return this == DRAFT;
    }

    /** Whether a goods receipt may be posted against an order in this state. */
    public boolean acceptsReceipts() {
        return RECEIVING.contains(this);
    }

    public boolean isTerminal() {
        return this == CLOSED || this == CANCELLED;
    }
}
