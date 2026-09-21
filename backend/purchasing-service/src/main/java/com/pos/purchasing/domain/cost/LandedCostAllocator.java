package com.pos.purchasing.domain.cost;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Spreads a delivery's freight and duty across the lines it arrived with.
 *
 * <p>Why this exists at all: the price on the invoice is not what the goods cost. A consignment
 * carrying 40,000 of freight sells at a loss if every item is priced off the supplier's unit price,
 * and the loss is invisible because each individual line looks profitable. Allocating the charges
 * at receipt puts the real cost on the batch, where every later margin calculation reads it.
 *
 * <p>Two rules hold absolutely:
 *
 * <ul>
 *   <li><b>Nothing is lost or invented.</b> The allocated amounts sum to exactly the charge. Naive
 *       proportional arithmetic loses fractions of a cent on almost every split, and a distribution
 *       that quietly totals 39,999.97 leaves an unexplained residual in the accounts. The rounding
 *       remainder is given to the largest line - the least distorted by it - and on a tie to the
 *       earliest of them, so posting the same delivery twice produces the same figures.
 *   <li><b>Pure.</b> No Spring, no JPA, no clock - so every awkward split can be tested directly.
 * </ul>
 */
public final class LandedCostAllocator {

    /** Money scale throughout the platform: NUMERIC(19,4). */
    private static final int MONEY_SCALE = 4;

    private LandedCostAllocator() {}

    /**
     * Allocates {@code charges} across {@code lines}.
     *
     * @param charges freight plus duty for the delivery as a whole; zero is valid and allocates
     *     nothing
     * @throws IllegalArgumentException if there are no lines, or a charge is negative
     */
    public static List<LineAllocation> allocate(
            List<ChargeableLine> lines, BigDecimal charges, AllocationBasis basis) {

        if (lines == null || lines.isEmpty()) {
            throw new IllegalArgumentException("Cannot allocate charges across no lines");
        }
        if (charges == null || charges.signum() < 0) {
            throw new IllegalArgumentException("Charges cannot be negative");
        }

        List<BigDecimal> weights = weights(lines, basis);
        BigDecimal totalWeight = weights.stream().reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal scaledCharges = charges.setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        List<BigDecimal> shares = new ArrayList<>(lines.size());

        for (int i = 0; i < lines.size(); i++) {
            if (scaledCharges.signum() == 0 || totalWeight.signum() == 0) {
                shares.add(BigDecimal.ZERO.setScale(MONEY_SCALE));
            } else {
                shares.add(
                        scaledCharges
                                .multiply(weights.get(i))
                                .divide(totalWeight, MONEY_SCALE, RoundingMode.HALF_UP));
            }
        }

        giveRemainderToLargestLine(shares, weights, scaledCharges);

        List<LineAllocation> allocations = new ArrayList<>(lines.size());
        for (int i = 0; i < lines.size(); i++) {
            ChargeableLine line = lines.get(i);
            BigDecimal share = shares.get(i);
            BigDecimal landedValue =
                    line.goodsValue().setScale(MONEY_SCALE, RoundingMode.HALF_UP).add(share);

            // The documented rounding step: the line's landed value is authoritative, and the unit
            // figure is derived from it for storage against the batch.
            BigDecimal landedUnitCost =
                    line.quantity().signum() == 0
                            ? BigDecimal.ZERO.setScale(MONEY_SCALE)
                            : landedValue.divide(
                                    line.quantity(), MONEY_SCALE, RoundingMode.HALF_UP);

            allocations.add(new LineAllocation(line.lineId(), share, landedValue, landedUnitCost));
        }
        return List.copyOf(allocations);
    }

    /**
     * What each line's share is proportional to.
     *
     * <p>Falls back to quantity when allocating by value would divide by zero - a delivery of free
     * samples has no value to be proportional to, and refusing to allocate would leave the freight
     * on those samples unaccounted for.
     */
    private static List<BigDecimal> weights(List<ChargeableLine> lines, AllocationBasis basis) {
        AllocationBasis effective = basis == null ? AllocationBasis.BY_VALUE : basis;

        if (effective == AllocationBasis.BY_VALUE) {
            BigDecimal totalValue =
                    lines.stream()
                            .map(ChargeableLine::goodsValue)
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            if (totalValue.signum() == 0) {
                effective = AllocationBasis.BY_QUANTITY;
            }
        }

        List<BigDecimal> weights = new ArrayList<>(lines.size());
        for (ChargeableLine line : lines) {
            weights.add(
                    effective == AllocationBasis.BY_VALUE ? line.goodsValue() : line.quantity());
        }
        return weights;
    }

    /**
     * Forces the shares to sum to the charge exactly.
     *
     * <p>The residual is at most a few units in the last place, but it has to land somewhere, and
     * "somewhere" must be the same line every time the same delivery is posted. Hence the strict
     * comparison below: equal-sized lines break the tie towards the earliest.
     */
    private static void giveRemainderToLargestLine(
            List<BigDecimal> shares, List<BigDecimal> weights, BigDecimal charges) {

        BigDecimal allocated = shares.stream().reduce(BigDecimal.ZERO, BigDecimal::add);
        BigDecimal remainder = charges.subtract(allocated);
        if (remainder.signum() == 0) {
            return;
        }

        int target = 0;
        for (int i = 1; i < weights.size(); i++) {
            if (weights.get(i).compareTo(weights.get(target)) > 0) {
                target = i;
            }
        }
        shares.set(target, shares.get(target).add(remainder));
    }

    /** The largest line by weight, exposed for callers that report where the residual went. */
    public static int largestLineIndex(List<ChargeableLine> lines, AllocationBasis basis) {
        List<BigDecimal> weights = weights(lines, basis);
        return weights.indexOf(weights.stream().max(Comparator.naturalOrder()).orElseThrow());
    }
}
