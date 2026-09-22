package com.pos.sales.domain.policy;

import java.math.BigDecimal;

/**
 * What should be in the drawer, and what is.
 *
 * <p>Kept as a value rather than computed inline at close, so the arithmetic can be shown to a
 * cashier who disputes a shortfall: float, plus cash taken, less cash refunded, less what went to
 * the safe.
 *
 * <p>Pure: no Spring, no JPA, no clock.
 */
public record TillReconciliation(
        BigDecimal openingFloat,
        BigDecimal cashSales,
        BigDecimal cashRefunds,
        BigDecimal cashDrops,
        BigDecimal expectedCash,
        BigDecimal countedCash,
        BigDecimal variance) {

    public static TillReconciliation of(
            BigDecimal openingFloat,
            BigDecimal cashSales,
            BigDecimal cashRefunds,
            BigDecimal cashDrops,
            BigDecimal countedCash) {

        BigDecimal expected = openingFloat.add(cashSales).subtract(cashRefunds).subtract(cashDrops);
        BigDecimal counted = countedCash == null ? BigDecimal.ZERO : countedCash;

        return new TillReconciliation(
                openingFloat,
                cashSales,
                cashRefunds,
                cashDrops,
                expected,
                counted,
                counted.subtract(expected));
    }

    /** Short, which is the direction that gets investigated. */
    public boolean isShort() {
        return variance.signum() < 0;
    }

    public boolean isOver() {
        return variance.signum() > 0;
    }

    public boolean balances() {
        return variance.signum() == 0;
    }
}
