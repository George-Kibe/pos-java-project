package com.pos.common.money;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.Currency;
import java.util.Objects;

/**
 * An amount with a currency, stored at scale 4 to match {@code NUMERIC(19,4)} in the database.
 *
 * <p>Retail arithmetic accumulates: a basket line is quantity times unit price, a discount is a
 * percentage of that, tax is extracted from or added to the result. Doing any of that in {@code
 * double} loses cents, and losing cents in a till that must reconcile exactly at close of shift is
 * not acceptable. Every amount in this system is a {@code Money} or a {@code BigDecimal}, never a
 * floating point number.
 *
 * <p>Intermediate values keep scale 4. Rounding to the minor unit happens once, deliberately, at
 * {@link #rounded()} - typically when a line total or a payable amount is finalised.
 */
public record Money(BigDecimal amount, Currency currency) implements Comparable<Money> {

    /** Scale carried through calculations, matching NUMERIC(19,4). */
    public static final int CALCULATION_SCALE = 4;

    /** Retail rounding: half up, the convention customers expect on a receipt. */
    public static final RoundingMode ROUNDING = RoundingMode.HALF_UP;

    public Money {
        Objects.requireNonNull(amount, "amount");
        Objects.requireNonNull(currency, "currency");
        amount = amount.setScale(CALCULATION_SCALE, ROUNDING);
    }

    public static Money of(BigDecimal amount, Currency currency) {
        return new Money(amount, currency);
    }

    public static Money of(String amount, String currencyCode) {
        return new Money(new BigDecimal(amount), Currency.getInstance(currencyCode));
    }

    public static Money zero(Currency currency) {
        return new Money(BigDecimal.ZERO, currency);
    }

    public Money add(Money other) {
        requireSameCurrency(other);
        return new Money(amount.add(other.amount), currency);
    }

    public Money subtract(Money other) {
        requireSameCurrency(other);
        return new Money(amount.subtract(other.amount), currency);
    }

    public Money multiply(BigDecimal factor) {
        return new Money(amount.multiply(factor), currency);
    }

    public Money negate() {
        return new Money(amount.negate(), currency);
    }

    /** Rounded to the currency's minor unit, e.g. 2 decimal places for KES or USD. */
    public Money rounded() {
        return new Money(
                amount.setScale(currency.getDefaultFractionDigits(), ROUNDING)
                        .setScale(CALCULATION_SCALE, ROUNDING),
                currency);
    }

    public boolean isZero() {
        return amount.signum() == 0;
    }

    public boolean isNegative() {
        return amount.signum() < 0;
    }

    public boolean isPositive() {
        return amount.signum() > 0;
    }

    @Override
    public int compareTo(Money other) {
        requireSameCurrency(other);
        return amount.compareTo(other.amount);
    }

    private void requireSameCurrency(Money other) {
        if (!currency.equals(other.currency)) {
            throw new IllegalArgumentException(
                    "currency mismatch: %s vs %s"
                            .formatted(
                                    currency.getCurrencyCode(), other.currency.getCurrencyCode()));
        }
    }

    @Override
    public String toString() {
        return currency.getCurrencyCode() + " " + amount.toPlainString();
    }
}
