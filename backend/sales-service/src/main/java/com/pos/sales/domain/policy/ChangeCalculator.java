package com.pos.sales.domain.policy;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Change due, and how to make it up.
 *
 * <p>The amount is trivial arithmetic; the denomination breakdown is not, and it is what a cashier
 * under time pressure actually needs. Greedy from the largest note down is correct for every real
 * currency, including Kenyan shillings - the denominations are each a multiple of the next, so the
 * greedy choice can never paint itself into a corner.
 *
 * <p>Pure: no Spring, no JPA, no clock.
 */
public final class ChangeCalculator {

    private static final int MONEY_SCALE = 2;

    /**
     * Kenyan notes and coins, largest first.
     *
     * <p>The 1-shilling coin is the smallest in practice, so a total ending in cents cannot be paid
     * out exactly - the remainder is reported rather than silently dropped, because a shop that
     * rounds in its own favour without saying so is a shop with a complaint coming.
     */
    private static final List<BigDecimal> KES_DENOMINATIONS =
            List.of(
                    new BigDecimal("1000"),
                    new BigDecimal("500"),
                    new BigDecimal("200"),
                    new BigDecimal("100"),
                    new BigDecimal("50"),
                    new BigDecimal("40"),
                    new BigDecimal("20"),
                    new BigDecimal("10"),
                    new BigDecimal("5"),
                    new BigDecimal("1"));

    private ChangeCalculator() {}

    /** Change for a cash sale, using the default denominations. */
    public static ChangeDue calculate(BigDecimal amountDue, BigDecimal tendered) {
        return calculate(amountDue, tendered, KES_DENOMINATIONS);
    }

    /**
     * Change for a cash sale.
     *
     * @throws IllegalArgumentException if the tender does not cover the sale - a till must refuse
     *     that rather than hand out negative change
     */
    public static ChangeDue calculate(
            BigDecimal amountDue, BigDecimal tendered, List<BigDecimal> denominations) {

        if (amountDue == null || tendered == null) {
            throw new IllegalArgumentException("Amount due and tender are both required");
        }
        if (amountDue.signum() < 0) {
            throw new IllegalArgumentException("Amount due cannot be negative");
        }
        if (tendered.compareTo(amountDue) < 0) {
            throw new IllegalArgumentException(
                    "Tender of %s does not cover %s".formatted(tendered, amountDue));
        }

        BigDecimal change =
                tendered.subtract(amountDue).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> ordered =
                denominations.stream().sorted(Comparator.reverseOrder()).toList();

        List<ChangeDue.DenominationCount> counts = new ArrayList<>();
        BigDecimal remaining = change;

        for (BigDecimal denomination : ordered) {
            if (remaining.compareTo(denomination) < 0) {
                continue;
            }
            BigDecimal[] divided = remaining.divideAndRemainder(denomination);
            int count = divided[0].intValueExact();
            if (count > 0) {
                counts.add(new ChangeDue.DenominationCount(denomination, count));
                remaining = divided[1];
            }
        }

        return new ChangeDue(change, List.copyOf(counts), remaining);
    }
}
