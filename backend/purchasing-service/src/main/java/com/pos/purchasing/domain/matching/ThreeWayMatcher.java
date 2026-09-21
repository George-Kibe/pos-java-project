package com.pos.purchasing.domain.matching;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.LinkedHashSet;
import java.util.List;
import java.util.Map;
import java.util.Set;
import java.util.UUID;

/**
 * Compares what was ordered, what arrived, and what is being billed.
 *
 * <p>The question this answers is "should we pay this?", and it is not the same as "does the total
 * look right". A supplier can invoice the correct grand total while billing for goods that never
 * arrived and under-billing something else, so the comparison is per product:
 *
 * <ul>
 *   <li><b>quantity</b> against the <em>receipt</em>, because what arrived is what is owed for -
 *       not what was ordered
 *   <li><b>price</b> against the <em>order</em>, because that is the price that was agreed
 *   <li><b>receipt</b> against the order, to surface goods nobody authorised
 * </ul>
 *
 * <p>The justified total is therefore quantity received at the ordered price. The verdict is that
 * figure against the invoice, within tolerance.
 *
 * <p>Pure: no Spring, no JPA, no clock.
 */
public final class ThreeWayMatcher {

    private static final int MONEY_SCALE = 4;

    private ThreeWayMatcher() {}

    /**
     * Matches one invoice against its order and receipt.
     *
     * @param orderLines what was agreed; may be empty for a delivery with no purchase order, in
     *     which case the received price is taken as agreed and no price variance can be found
     * @param receivedLines what arrived and was accepted
     * @param invoiceLines what is being billed
     */
    public static MatchResult match(
            List<MatchableLine> orderLines,
            List<MatchableLine> receivedLines,
            List<MatchableLine> invoiceLines,
            MatchTolerance tolerance) {

        Map<UUID, MatchableLine> ordered = byProduct(orderLines);
        Map<UUID, MatchableLine> received = byProduct(receivedLines);
        Map<UUID, MatchableLine> invoiced = byProduct(invoiceLines);

        List<LineVariance> variances = new ArrayList<>();
        BigDecimal justifiedTotal = BigDecimal.ZERO.setScale(MONEY_SCALE);
        BigDecimal invoicedTotal = BigDecimal.ZERO.setScale(MONEY_SCALE);

        Set<UUID> products = new LinkedHashSet<>();
        products.addAll(received.keySet());
        products.addAll(invoiced.keySet());
        products.addAll(ordered.keySet());

        for (UUID productId : products) {
            MatchableLine orderLine = ordered.get(productId);
            MatchableLine receiptLine = received.get(productId);
            MatchableLine invoiceLine = invoiced.get(productId);
            String sku = firstSku(invoiceLine, receiptLine, orderLine);

            // The agreed price, or the received price when there is no order to agree with.
            BigDecimal agreedUnitCost =
                    orderLine != null
                            ? orderLine.unitCost()
                            : receiptLine != null ? receiptLine.unitCost() : null;

            if (receiptLine != null && agreedUnitCost != null) {
                justifiedTotal =
                        justifiedTotal.add(
                                receiptLine
                                        .quantity()
                                        .multiply(agreedUnitCost)
                                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            }
            if (invoiceLine != null) {
                invoicedTotal =
                        invoicedTotal.add(
                                invoiceLine
                                        .lineTotal()
                                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP));
            }

            if (invoiceLine != null && receiptLine == null) {
                variances.add(
                        new LineVariance(
                                productId,
                                sku,
                                VarianceType.NOT_RECEIVED,
                                BigDecimal.ZERO,
                                invoiceLine.quantity(),
                                invoiceLine.quantity(),
                                invoiceLine.lineTotal().setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                                "Invoiced but never received"));
                continue;
            }

            if (receiptLine != null && invoiceLine == null) {
                variances.add(
                        new LineVariance(
                                productId,
                                sku,
                                VarianceType.NOT_INVOICED,
                                receiptLine.quantity(),
                                BigDecimal.ZERO,
                                receiptLine.quantity().negate(),
                                BigDecimal.ZERO.setScale(MONEY_SCALE),
                                "Received but not on this invoice"));
            }

            if (receiptLine != null && orderLine != null) {
                BigDecimal overReceipt = receiptLine.quantity().subtract(orderLine.quantity());
                if (overReceipt.signum() > 0) {
                    variances.add(
                            new LineVariance(
                                    productId,
                                    sku,
                                    VarianceType.OVER_RECEIPT,
                                    orderLine.quantity(),
                                    receiptLine.quantity(),
                                    overReceipt,
                                    overReceipt
                                            .multiply(orderLine.unitCost())
                                            .setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                                    "More received than ordered"));
                }
            }

            if (invoiceLine == null || receiptLine == null) {
                continue;
            }

            BigDecimal quantityDifference = invoiceLine.quantity().subtract(receiptLine.quantity());
            if (quantityDifference.signum() != 0) {
                variances.add(
                        new LineVariance(
                                productId,
                                sku,
                                VarianceType.QUANTITY_VARIANCE,
                                receiptLine.quantity(),
                                invoiceLine.quantity(),
                                quantityDifference,
                                quantityDifference
                                        .multiply(agreedUnitCost)
                                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                                "Invoiced quantity differs from what was received"));
            }

            BigDecimal priceDifference = invoiceLine.unitCost().subtract(agreedUnitCost);
            if (priceDifference.signum() != 0) {
                variances.add(
                        new LineVariance(
                                productId,
                                sku,
                                VarianceType.PRICE_VARIANCE,
                                agreedUnitCost,
                                invoiceLine.unitCost(),
                                priceDifference,
                                priceDifference
                                        .multiply(receiptLine.quantity())
                                        .setScale(MONEY_SCALE, RoundingMode.HALF_UP),
                                orderLine == null
                                        ? "Invoiced above the price on the receipt"
                                        : "Invoiced above the price on the order"));
            }
        }

        BigDecimal variance = invoicedTotal.subtract(justifiedTotal);
        MatchTolerance effective = tolerance == null ? MatchTolerance.exact() : tolerance;

        MatchVerdict verdict;
        if (variance.signum() == 0 && variances.isEmpty()) {
            verdict = MatchVerdict.MATCHED;
        } else if (variance.abs().compareTo(effective.allowedOn(justifiedTotal)) <= 0
                && noStructuralProblem(variances)) {
            verdict = MatchVerdict.WITHIN_TOLERANCE;
        } else {
            verdict = MatchVerdict.EXCEPTION;
        }

        return new MatchResult(
                verdict, invoicedTotal, justifiedTotal, variance, List.copyOf(variances));
    }

    /**
     * Some findings are not a matter of degree.
     *
     * <p>Being billed for goods that never arrived, or receiving goods nobody ordered, is wrong at
     * any size - a tolerance is there to absorb rounding and small price drift, not to wave through
     * a delivery that does not exist. A quantity variance that nets out to a small amount of money
     * is the shape a mis-shipment takes, so it is not tolerated either.
     */
    private static boolean noStructuralProblem(List<LineVariance> variances) {
        return variances.stream()
                .noneMatch(
                        v ->
                                v.type() == VarianceType.NOT_RECEIVED
                                        || v.type() == VarianceType.OVER_RECEIPT
                                        || v.type() == VarianceType.QUANTITY_VARIANCE);
    }

    private static Map<UUID, MatchableLine> byProduct(List<MatchableLine> lines) {
        Map<UUID, MatchableLine> byProduct = new LinkedHashMap<>();
        if (lines == null) {
            return byProduct;
        }
        for (MatchableLine line : lines) {
            // A repeated product on one document is summed rather than refused: suppliers do split
            // a product across two invoice lines, and it is still one thing being billed for.
            byProduct.merge(
                    line.productId(),
                    line,
                    (existing, incoming) ->
                            new MatchableLine(
                                    existing.productId(),
                                    existing.sku(),
                                    existing.quantity().add(incoming.quantity()),
                                    existing.unitCost()));
        }
        return byProduct;
    }

    private static String firstSku(MatchableLine... candidates) {
        for (MatchableLine candidate : candidates) {
            if (candidate != null && candidate.sku() != null) {
                return candidate.sku();
            }
        }
        return null;
    }
}
