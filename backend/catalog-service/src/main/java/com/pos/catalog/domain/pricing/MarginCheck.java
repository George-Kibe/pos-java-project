package com.pos.catalog.domain.pricing;

import java.math.BigDecimal;
import java.math.RoundingMode;

/**
 * What an item earns at its price, given what it cost - and, when that is less than the business
 * wants, the price that would earn it.
 *
 * <p>Everything is compared without tax. Output VAT is collected for the tax authority and input
 * VAT is reclaimed from it, so neither is the business's money; a margin computed on a
 * tax-inclusive shelf price against a tax-exclusive cost would flatter every standard-rated item by
 * the tax rate.
 *
 * <p>Margin is on the selling price, {@code (price - cost) / price}, the figure the margin reports
 * show, so a 15% target here and 15% on a report mean the same thing.
 *
 * @param netCost what one unit cost, without tax
 * @param netPrice what one unit sells for, without tax
 * @param margin the margin at that price; null when the price is zero
 * @param targetMargin the margin wanted, as a fraction; null when none is set
 * @param suggestedPrice a price that reaches the target (or at least covers cost when there is no
 *     target), expressed as the price is entered - with tax when the product's price includes it -
 *     and rounded up to the whole shilling; null when the price is already fine
 */
public record MarginCheck(
        BigDecimal netCost,
        BigDecimal netPrice,
        BigDecimal margin,
        BigDecimal targetMargin,
        Status status,
        BigDecimal suggestedPrice) {

    public enum Status {
        /** At or above the target. */
        OK,
        /** No target is set and the price covers cost. */
        NO_TARGET,
        /** Covers cost, but earns less than the target. */
        BELOW_TARGET,
        /** Sells for less than it cost. */
        BELOW_COST
    }

    private static final int SCALE = 4;

    /** Takes the tax out of an amount that includes it. */
    public static BigDecimal withoutTax(BigDecimal amount, BigDecimal taxRate) {
        return amount.divide(BigDecimal.ONE.add(taxRate), SCALE, RoundingMode.HALF_UP);
    }

    /**
     * @param netCost one unit's cost without tax
     * @param price the selling price as entered in the catalogue
     * @param priceIncludesTax whether {@code price} contains tax
     * @param taxRate the product's rate, as a fraction
     * @param targetMargin the wanted margin as a fraction, or null
     */
    public static MarginCheck of(
            BigDecimal netCost,
            BigDecimal price,
            boolean priceIncludesTax,
            BigDecimal taxRate,
            BigDecimal targetMargin) {

        if (targetMargin != null
                && (targetMargin.signum() < 0 || targetMargin.compareTo(BigDecimal.ONE) >= 0)) {
            throw new IllegalArgumentException("A target margin is at least 0 and below 100%");
        }
        BigDecimal netPrice =
                priceIncludesTax
                        ? withoutTax(price, taxRate)
                        : price.setScale(SCALE, RoundingMode.HALF_UP);
        BigDecimal margin =
                netPrice.signum() == 0
                        ? null
                        : netPrice.subtract(netCost).divide(netPrice, SCALE, RoundingMode.HALF_UP);

        Status status;
        if (netPrice.compareTo(netCost) < 0) {
            status = Status.BELOW_COST;
        } else if (targetMargin == null) {
            status = Status.NO_TARGET;
        } else if (margin == null || margin.compareTo(targetMargin) < 0) {
            status = Status.BELOW_TARGET;
        } else {
            status = Status.OK;
        }

        BigDecimal suggested = null;
        if (status == Status.BELOW_COST || status == Status.BELOW_TARGET) {
            // Without a target, the least that stops the loss: the cost itself.
            BigDecimal wanted = targetMargin == null ? BigDecimal.ZERO : targetMargin;
            BigDecimal netSuggested =
                    netCost.divide(BigDecimal.ONE.subtract(wanted), 10, RoundingMode.HALF_UP);
            BigDecimal asEntered =
                    priceIncludesTax
                            ? netSuggested.multiply(BigDecimal.ONE.add(taxRate))
                            : netSuggested;
            // Up, never to the nearest: rounding down would land just short of the target.
            suggested = asEntered.setScale(0, RoundingMode.CEILING).setScale(SCALE);
        }
        return new MarginCheck(netCost, netPrice, margin, targetMargin, status, suggested);
    }
}
