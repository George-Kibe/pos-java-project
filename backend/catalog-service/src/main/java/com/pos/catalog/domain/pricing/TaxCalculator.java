package com.pos.catalog.domain.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

import com.pos.common.money.Money;

/**
 * Splits an amount into net and tax.
 *
 * <p>Two directions, and they are not interchangeable. An inclusive price already contains tax, so
 * tax is <em>extracted</em> from it: the shelf price is what the customer pays and is the source of
 * truth. An exclusive price has tax <em>added</em>. Adding tax on top of an inclusive price
 * overcharges by the rate, which on 16% VAT is not a rounding error anybody will miss.
 *
 * <p>Rounding happens once, on the tax figure, and the remaining part is then derived by
 * subtraction. Rounding net and tax separately lets them drift a cent apart from the gross, and a
 * cent per line becomes a real number over a day's trading.
 */
public final class TaxCalculator {

    private TaxCalculator() {}

    /** Working precision, above the two decimal places a customer sees. */
    private static final int WORKING_SCALE = 10;

    /**
     * Extracts tax from an amount that already contains it.
     *
     * <p>{@code tax = gross × rate / (1 + rate)}. The naive {@code gross × rate} is the classic
     * error: on 100 at 16% it gives 16, when the correct answer is 13.79, because the 100 is 116%
     * of the net amount rather than 100% of it.
     */
    public static TaxCalculation extractFrom(Money gross, BigDecimal rate) {
        requireSaneRate(rate);

        BigDecimal taxRaw =
                gross.amount()
                        .multiply(rate)
                        .divide(BigDecimal.ONE.add(rate), WORKING_SCALE, RoundingMode.HALF_UP);

        Money tax = roundToMinorUnit(Money.of(taxRaw, gross.currency()));
        Money net = gross.subtract(tax);

        return new TaxCalculation(net, tax, gross, rate, true);
    }

    /** Adds tax to an amount that does not contain it: {@code tax = net × rate}. */
    public static TaxCalculation addTo(Money net, BigDecimal rate) {
        requireSaneRate(rate);

        Money tax = roundToMinorUnit(Money.of(net.amount().multiply(rate), net.currency()));
        Money gross = net.add(tax);

        return new TaxCalculation(net, tax, gross, rate, false);
    }

    /** Splits {@code amount} according to whether it is quoted inclusive of tax. */
    public static TaxCalculation split(Money amount, BigDecimal rate, boolean inclusive) {
        return inclusive ? extractFrom(amount, rate) : addTo(amount, rate);
    }

    private static Money roundToMinorUnit(Money money) {
        return money.rounded();
    }

    private static void requireSaneRate(BigDecimal rate) {
        if (rate == null || rate.signum() < 0 || rate.compareTo(BigDecimal.ONE) >= 0) {
            // A rate of 1 or more would make an inclusive price mathematically impossible.
            throw new IllegalArgumentException(
                    "Tax rate must be at least 0 and below 1, was " + rate);
        }
    }
}
