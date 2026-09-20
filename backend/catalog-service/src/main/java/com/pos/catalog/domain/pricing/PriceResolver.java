package com.pos.catalog.domain.pricing;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

import com.pos.catalog.domain.PromotionType;
import com.pos.common.money.Money;

/**
 * Works out what one line costs.
 *
 * <p>The order is fixed and matters: subtotal, then discounts, then tax. Tax last because a
 * discount changes the taxable amount - charging tax on the undiscounted price and then discounting
 * the total overcharges the customer and understates nothing on the VAT return, which is the worse
 * of the two ways to get it wrong.
 *
 * <p>Pure: no database, no clock, no Spring. Everything it needs is in the request, so the same
 * inputs always produce the same breakdown, and every combination can be tested directly.
 */
public final class PriceResolver {

    private PriceResolver() {}

    /** Promotions are applied in this order so two tills price a basket identically. */
    private static final Comparator<PromotionCandidate> DETERMINISTIC_ORDER =
            Comparator.comparingInt(PromotionCandidate::priority)
                    .thenComparing(PromotionCandidate::code);

    public static PriceBreakdown resolve(PricingRequest request) {
        Money unitPrice = request.unitPrice();
        BigDecimal quantity = request.quantity();

        if (quantity == null || quantity.signum() <= 0) {
            throw new IllegalArgumentException("Quantity must be positive, was " + quantity);
        }

        Money subtotal = unitPrice.multiply(quantity).rounded();

        List<AppliedDiscount> applied = applyPromotions(request, unitPrice, quantity, subtotal);

        Money discountTotal = sum(applied, subtotal.currency());
        Money discountedSubtotal = subtotal.subtract(discountTotal);

        TaxCalculation tax =
                TaxCalculator.split(discountedSubtotal, request.taxRate(), request.taxInclusive());

        // Inclusive: the discounted subtotal already is what the customer pays. Exclusive: tax is
        // added on top. Either way net + tax equals the line total exactly.
        Money lineTotal = request.taxInclusive() ? discountedSubtotal : tax.gross();

        return new PriceBreakdown(
                request.productId(),
                request.sku(),
                request.productName(),
                quantity,
                unitPrice,
                request.priceSource(),
                request.priceListId(),
                request.taxInclusive(),
                subtotal,
                List.copyOf(applied),
                discountTotal,
                discountedSubtotal,
                request.taxClassCode(),
                request.taxRate(),
                tax.net(),
                tax.tax(),
                lineTotal);
    }

    /**
     * Chooses and applies the promotions.
     *
     * <p>If any eligible promotion is marked non-stackable, exactly one promotion is applied: the
     * one worth most to the customer. That rule is deliberate - "best single offer" is what
     * shoppers expect and what shelf-edge labels imply, and silently combining offers that were
     * never meant to combine is how a margin disappears.
     *
     * <p>Otherwise every eligible promotion applies, in priority order, each calculated on the
     * amount left after the previous one. Sequential rather than all-on-the-original, because two
     * 50% offers should leave the customer paying a quarter, not nothing.
     */
    private static List<AppliedDiscount> applyPromotions(
            PricingRequest request, Money unitPrice, BigDecimal quantity, Money subtotal) {

        List<PromotionCandidate> eligible =
                request.promotions().stream()
                        .filter(promotion -> isEligible(promotion, quantity))
                        .sorted(DETERMINISTIC_ORDER)
                        .toList();

        if (eligible.isEmpty()) {
            return List.of();
        }

        boolean anyExclusive = eligible.stream().anyMatch(promotion -> !promotion.stackable());
        if (anyExclusive) {
            return List.of(bestSingle(eligible, unitPrice, quantity, subtotal));
        }

        List<AppliedDiscount> applied = new ArrayList<>();
        Money running = subtotal;
        for (PromotionCandidate promotion : eligible) {
            Money discount = discountFor(promotion, unitPrice, quantity, running);
            if (discount.isPositive()) {
                applied.add(toApplied(promotion, discount));
                running = running.subtract(discount);
            }
        }
        return applied;
    }

    /** The single most valuable promotion. Ties resolve by the deterministic order. */
    private static AppliedDiscount bestSingle(
            List<PromotionCandidate> eligible,
            Money unitPrice,
            BigDecimal quantity,
            Money subtotal) {

        PromotionCandidate best = null;
        Money bestDiscount = Money.zero(subtotal.currency());

        for (PromotionCandidate promotion : eligible) {
            Money discount = discountFor(promotion, unitPrice, quantity, subtotal);
            if (discount.compareTo(bestDiscount) > 0) {
                best = promotion;
                bestDiscount = discount;
            }
        }

        if (best == null) {
            // Every candidate works out to nothing; report the first in deterministic order so the
            // receipt still names the offer the shelf label promised.
            best = eligible.get(0);
        }
        return toApplied(best, bestDiscount);
    }

    private static boolean isEligible(PromotionCandidate promotion, BigDecimal quantity) {
        if (promotion.type() == PromotionType.BUNDLE) {
            // A bundle spans several products, so it can only be judged against a whole basket.
            // sales-service evaluates those; pricing one line cannot see the rest.
            return false;
        }
        if (promotion.minQuantity() != null && quantity.compareTo(promotion.minQuantity()) < 0) {
            return false;
        }
        return true;
    }

    /** Never more than what is left, and never negative: a line cannot pay the customer. */
    private static Money discountFor(
            PromotionCandidate promotion, Money unitPrice, BigDecimal quantity, Money running) {

        Money raw =
                switch (promotion.type()) {
                    case PERCENTAGE_OFF -> running.multiply(nullSafe(promotion.value())).rounded();
                    case AMOUNT_OFF ->
                            Money.of(nullSafe(promotion.value()), running.currency())
                                    .multiply(quantity)
                                    .rounded();
                    case BUY_X_GET_Y -> buyXGetY(promotion, unitPrice, quantity);
                    case BUNDLE -> Money.zero(running.currency());
                };

        if (raw.isNegative()) {
            return Money.zero(running.currency());
        }
        return raw.compareTo(running) > 0 ? running : raw;
    }

    /**
     * Buy X get Y free: whole groups of {@code X + Y} each yield {@code Y} free units.
     *
     * <p>Only whole groups count. Buy three on a three-for-two and you get one free; buy five and
     * you still get one, because the fourth and fifth do not complete a second group.
     */
    private static Money buyXGetY(
            PromotionCandidate promotion, Money unitPrice, BigDecimal quantity) {
        BigDecimal buy = nullSafe(promotion.buyQuantity());
        BigDecimal get = nullSafe(promotion.getQuantity());
        BigDecimal groupSize = buy.add(get);

        if (groupSize.signum() <= 0 || get.signum() <= 0) {
            return Money.zero(unitPrice.currency());
        }

        BigDecimal groups = quantity.divideToIntegralValue(groupSize);
        BigDecimal freeUnits = groups.multiply(get);
        return unitPrice.multiply(freeUnits).rounded();
    }

    private static AppliedDiscount toApplied(PromotionCandidate promotion, Money amount) {
        return new AppliedDiscount(
                promotion.id(),
                promotion.code(),
                promotion.name(),
                promotion.type().name(),
                amount);
    }

    private static Money sum(List<AppliedDiscount> discounts, java.util.Currency currency) {
        Money total = Money.zero(currency);
        for (AppliedDiscount discount : discounts) {
            total = total.add(discount.amount());
        }
        return total;
    }

    private static BigDecimal nullSafe(BigDecimal value) {
        return value == null ? BigDecimal.ZERO : value;
    }
}
