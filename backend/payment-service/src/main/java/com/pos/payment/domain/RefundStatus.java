package com.pos.payment.domain;

public enum RefundStatus {
    /** An M-Pesa reversal waiting for the dispatcher. */
    PENDING_DISPATCH,
    /** Reversal requested; Daraja answers asynchronously. */
    PROCESSING,
    /** Card: the cashier runs the refund on the terminal and keys in its reference. */
    AWAITING_CAPTURE,
    /** The provider cannot do it on its own; a person must settle it and say how. */
    REQUIRES_ACTION,
    COMPLETED
}
