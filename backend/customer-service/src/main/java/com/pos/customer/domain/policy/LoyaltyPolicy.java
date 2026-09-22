package com.pos.customer.domain.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What a shilling earns and what a point is worth.
 *
 * <p>Both directions round in the shop's favour, and deliberately: points earned are rounded
 * <b>down</b> and points needed to pay are rounded <b>up</b>. Rounding a part-point into existence
 * would let a member pay for something with points they did not earn, and the error would compound
 * over a million baskets.
 *
 * <p>Pure: no Spring, no JPA, no clock.
 *
 * @param spendPerPoint how much must be spent to earn one point
 * @param pointValue what one point is worth when it is spent
 */
public record LoyaltyPolicy(BigDecimal spendPerPoint, BigDecimal pointValue) {

    public LoyaltyPolicy {
        if (spendPerPoint == null || spendPerPoint.signum() <= 0) {
            throw new IllegalArgumentException("Spend per point must be positive");
        }
        if (pointValue == null || pointValue.signum() <= 0) {
            throw new IllegalArgumentException("A point must be worth something");
        }
    }

    /** Points earned by a spend at this multiplier. Never negative, always whole. */
    public long pointsFor(BigDecimal spend, BigDecimal tierMultiplier) {
        if (spend == null || spend.signum() <= 0) {
            return 0;
        }
        BigDecimal multiplier = tierMultiplier == null ? BigDecimal.ONE : tierMultiplier;
        return spend.multiply(multiplier)
                .divide(spendPerPoint, 0, RoundingMode.DOWN)
                .longValueExact();
    }

    /** What these points are worth as money. */
    public BigDecimal valueOf(long points) {
        return pointValue.multiply(BigDecimal.valueOf(points));
    }

    /** Points needed to cover an amount; a part point cannot be spent, so this rounds up. */
    public long pointsToCover(BigDecimal amount) {
        if (amount == null || amount.signum() <= 0) {
            return 0;
        }
        return amount.divide(pointValue, 0, RoundingMode.UP).longValueExact();
    }
}
