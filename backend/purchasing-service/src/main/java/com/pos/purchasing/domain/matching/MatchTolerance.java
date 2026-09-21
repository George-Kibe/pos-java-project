package com.pos.purchasing.domain.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * How much disagreement is not worth a phone call.
 *
 * <p>Both limits apply and the more generous one wins. A percentage alone makes a 20-shilling
 * invoice unpayable over a 1-shilling rounding difference; an absolute amount alone treats a
 * 2,000-shilling variance on a 4,000,000 order as an exception. Real purchasing departments use
 * both.
 *
 * @param absoluteAmount a variance at or below this is tolerated whatever the invoice size
 * @param percentage a fraction, so 0.02 is two percent, applied to the justified total
 */
public record MatchTolerance(BigDecimal absoluteAmount, BigDecimal percentage) {

    private static final int MONEY_SCALE = 4;

    public MatchTolerance {
        if (absoluteAmount == null || absoluteAmount.signum() < 0) {
            throw new IllegalArgumentException("Tolerance amount cannot be negative");
        }
        if (percentage == null || percentage.signum() < 0) {
            throw new IllegalArgumentException("Tolerance percentage cannot be negative");
        }
    }

    /** Nothing is tolerated: the figures must agree exactly. */
    public static MatchTolerance exact() {
        return new MatchTolerance(BigDecimal.ZERO, BigDecimal.ZERO);
    }

    /** The allowed variance on a given justified total. */
    public BigDecimal allowedOn(BigDecimal justifiedTotal) {
        BigDecimal proportional =
                justifiedTotal
                        .abs()
                        .multiply(percentage)
                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        return proportional.max(absoluteAmount.setScale(MONEY_SCALE, RoundingMode.HALF_UP));
    }
}
