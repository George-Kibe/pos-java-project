package com.pos.payment.domain.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What M-Pesa can actually be asked for.
 *
 * <p>Daraja takes whole shillings only, while a sale's total carries four decimals. This is the
 * documented rounding step for M-Pesa: the charge is the amount rounded {@code HALF_UP} to a whole
 * shilling, never less than one. A customer who pays exactly that charge has settled the amount in
 * full - reporting 1052 as "part paid" against 1052.40 would leave the sale waiting for forty cents
 * nobody can send.
 *
 * <p>Pure: no Spring, no JPA.
 */
public final class MpesaAmount {

    private MpesaAmount() {}

    /** The whole-shilling amount to push for {@code amount}. */
    public static BigDecimal chargeFor(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            throw new IllegalArgumentException("An M-Pesa charge needs a positive amount");
        }
        BigDecimal whole = amount.setScale(0, RoundingMode.HALF_UP);
        return whole.signum() == 0 ? BigDecimal.ONE : whole;
    }

    /**
     * How much of the intent a provider payment settles.
     *
     * <p>The full intent amount when the customer paid exactly the rounded charge; otherwise what
     * was actually paid, so a short payment is reported as short and not rounded into a full one.
     */
    public static BigDecimal settles(BigDecimal intentAmount, BigDecimal paid) {
        if (paid == null) {
            return intentAmount;
        }
        return paid.compareTo(chargeFor(intentAmount)) == 0 ? intentAmount : paid;
    }
}
