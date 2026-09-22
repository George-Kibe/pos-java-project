package com.pos.payment.domain.policy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Spreads a refund across the payments of one method on a sale.
 *
 * <p>Usually one payment, but a basket can be paid by two M-Pesa pushes. The largest refundable
 * payment is drawn on first, so a full refund of a single payment stays a single - and therefore
 * reversible - refund. Whatever cannot be covered is reported as {@code uncovered}, never silently
 * dropped: a customer owed money that the system cannot find a payment for is a complaint, not a
 * rounding error.
 *
 * <p>Pure: no Spring, no JPA.
 */
public final class RefundAllocation {

    private RefundAllocation() {}

    /** A payment that can still be refunded. */
    public record Refundable(UUID paymentId, BigDecimal amount, BigDecimal alreadyRefunded) {
        BigDecimal remaining() {
            return amount.subtract(alreadyRefunded);
        }
    }

    /**
     * @param full true when this share returns the whole payment and nothing was refunded from it
     *     before, which is the only shape M-Pesa can reverse
     */
    public record Share(UUID paymentId, BigDecimal amount, boolean full) {}

    public record Plan(List<Share> shares, BigDecimal uncovered) {}

    public static Plan allocate(BigDecimal refund, List<Refundable> payments) {
        if (refund == null || refund.signum() <= 0) {
            throw new IllegalArgumentException("A refund needs a positive amount");
        }
        List<Refundable> ordered =
                payments.stream()
                        .filter(payment -> payment.remaining().signum() > 0)
                        .sorted(Comparator.comparing(Refundable::remaining).reversed())
                        .toList();

        List<Share> shares = new ArrayList<>();
        BigDecimal left = refund;
        for (Refundable payment : ordered) {
            if (left.signum() <= 0) {
                break;
            }
            BigDecimal share = left.min(payment.remaining());
            boolean full =
                    payment.alreadyRefunded().signum() == 0
                            && share.compareTo(payment.amount()) == 0;
            shares.add(new Share(payment.paymentId(), share, full));
            left = left.subtract(share);
        }
        return new Plan(List.copyOf(shares), left.max(BigDecimal.ZERO));
    }
}
