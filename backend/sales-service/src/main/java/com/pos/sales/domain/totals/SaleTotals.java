package com.pos.sales.domain.totals;

import java.math.BigDecimal;
import java.util.List;

/**
 * What the sale comes to, and the working behind it.
 *
 * @param taxBreakdown one row per tax class, in a stable order, summing to {@code net} and {@code
 *     tax}
 */
public record SaleTotals(
        BigDecimal net,
        BigDecimal tax,
        BigDecimal discount,
        BigDecimal grand,
        List<TaxClassTotal> taxBreakdown) {

    /** True when this sale's figures agree with what a client claimed, to the cent. */
    public boolean agreesWith(BigDecimal claimedGrandTotal) {
        return claimedGrandTotal != null && grand.compareTo(claimedGrandTotal) == 0;
    }

    /** How far a claimed total is out. Positive when the client claimed more than is owed. */
    public BigDecimal varianceAgainst(BigDecimal claimedGrandTotal) {
        return claimedGrandTotal == null ? BigDecimal.ZERO : claimedGrandTotal.subtract(grand);
    }
}
