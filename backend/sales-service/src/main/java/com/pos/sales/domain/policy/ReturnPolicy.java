package com.pos.sales.domain.policy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

/**
 * The returns rules.
 *
 * <p>Two things are being decided, and they are separate on purpose. Whether the goods may come
 * back at all is arithmetic over what was sold and what has already been returned, and no human
 * judgement improves it. Whether an out-of-window return is accepted anyway is a commercial
 * decision, so the policy reports that an override is needed rather than refusing outright - and
 * the caller records who took it.
 *
 * <p>Pure: no Spring, no JPA, and the clock is a parameter, so "31 days later" is a test rather
 * than a wait.
 */
public final class ReturnPolicy {

    private ReturnPolicy() {}

    /**
     * Whether this much of a sale line may be returned.
     *
     * @param soldQuantity what the original line sold
     * @param alreadyReturned what has come back already, across every previous return
     * @param requestedQuantity what is being returned now
     * @param windowDays the shop's returns window; zero or negative means no window is enforced
     */
    public static ReturnEligibility evaluate(
            UUID saleLineId,
            BigDecimal soldQuantity,
            BigDecimal alreadyReturned,
            BigDecimal requestedQuantity,
            Instant soldAt,
            Instant now,
            int windowDays) {

        BigDecimal returned = alreadyReturned == null ? BigDecimal.ZERO : alreadyReturned;
        BigDecimal remaining = soldQuantity.subtract(returned);

        if (remaining.signum() <= 0) {
            return ReturnEligibility.refused(
                    saleLineId, returned, "Every unit on this line has already been returned");
        }
        if (requestedQuantity == null || requestedQuantity.signum() <= 0) {
            return ReturnEligibility.refused(
                    saleLineId, returned, "A return needs a quantity greater than zero");
        }
        if (requestedQuantity.compareTo(remaining) > 0) {
            return ReturnEligibility.refused(
                    saleLineId,
                    returned,
                    "Only %s of this line is left to return".formatted(stripped(remaining)));
        }

        boolean outsideWindow = windowDays > 0 && daysBetween(soldAt, now) > windowDays;
        return ReturnEligibility.allowed(saleLineId, remaining, returned, outsideWindow);
    }

    /**
     * How much to refund for a partial return.
     *
     * <p>Pro-rated from the line as sold, so a discount the customer received is honoured on the
     * way back too. Refunding the undiscounted price on a promotional item hands out more than was
     * taken, which is a slow leak nobody notices until stocktake.
     */
    public static BigDecimal refundFor(
            BigDecimal soldQuantity, BigDecimal lineTotal, BigDecimal returnedQuantity) {

        if (soldQuantity.signum() == 0) {
            return BigDecimal.ZERO.setScale(4);
        }
        if (returnedQuantity.compareTo(soldQuantity) == 0) {
            // The whole line: give back exactly what was charged, with no division at all.
            return lineTotal;
        }
        return lineTotal
                .multiply(returnedQuantity)
                .divide(soldQuantity, 4, java.math.RoundingMode.HALF_UP);
    }

    /** Whole days elapsed, so a return the next morning is one day old rather than nearly two. */
    public static long daysBetween(Instant soldAt, Instant now) {
        return Duration.between(soldAt, now).toDays();
    }

    private static String stripped(BigDecimal value) {
        return value.stripTrailingZeros().toPlainString();
    }
}
