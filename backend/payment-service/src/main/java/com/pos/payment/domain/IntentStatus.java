package com.pos.payment.domain;

/** Where one tender is on its way to money. */
public enum IntentStatus {
    /** Accepted from sales; waiting for the dispatcher. */
    REQUESTED,
    /** Claimed by the dispatcher; the provider is being called. */
    DISPATCHING,
    /** STK Push sent; the customer's phone is showing the prompt. */
    AWAITING_CUSTOMER,
    /** Card: the cashier runs the terminal and keys in the approval code. */
    AWAITING_CAPTURE,
    AUTHORIZED,
    FAILED;

    public boolean isFinal() {
        return this == AUTHORIZED || this == FAILED;
    }
}
