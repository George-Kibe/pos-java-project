package com.pos.sales.domain;

/** Cash in and out of a drawer, other than sales and refunds. */
public enum CashMovementType {
    /** The opening float, or a top-up of small change mid-shift. */
    FLOAT_IN,

    /** To the safe, so the drawer does not hold more than it should. */
    DROP,

    /** Petty cash out: a delivery driver, a refund from another lane. */
    PAY_OUT,

    /** A keying error being put right, always with a reason. */
    CORRECTION;

    /** Whether this movement takes cash out of the drawer. */
    public boolean isOutward() {
        return this == DROP || this == PAY_OUT;
    }
}
