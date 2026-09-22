package com.pos.sales.domain.totals;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;

/**
 * Adds a basket up.
 *
 * <p>Deliberately nothing more than addition. Every per-line figure arrives already computed by
 * catalog's pricing engine, and this class's one job is to sum them and group them by tax class
 * without ever recalculating a tax amount. That restraint is the whole design: the roadmap requires
 * a receipt whose breakdown matches catalog exactly, and the only way to guarantee that is to have
 * one implementation of the arithmetic and carry its answers.
 *
 * <p>What it does add is the grouping, which nothing else does: a basket has many lines and a
 * receipt shows one row per tax class, so the rows have to be aggregated somewhere and the sum has
 * to come out identical to the line-by-line total.
 *
 * <p>Pure: no Spring, no JPA, no clock.
 */
public final class SaleTotalsCalculator {

    private static final int MONEY_SCALE = 4;

    private SaleTotalsCalculator() {}

    /**
     * Totals a basket.
     *
     * <p>An empty basket totals zero rather than throwing: a cart with every line voided is a real
     * state a cashier can reach, and the checkout refuses it for its own reasons with a message
     * about the basket rather than an arithmetic error.
     */
    public static SaleTotals total(List<PricedLine> lines) {
        BigDecimal net = zero();
        BigDecimal tax = zero();
        BigDecimal discount = zero();
        BigDecimal grand = zero();

        // Insertion-ordered so the breakdown is stable for a given basket; sorted below so it is
        // stable across baskets too, which matters when two receipts are compared.
        Map<String, Accumulator> byClass = new LinkedHashMap<>();

        for (PricedLine line : lines) {
            net = net.add(line.netAmount());
            tax = tax.add(line.taxAmount());
            discount = discount.add(line.discountTotal());
            grand = grand.add(line.lineTotal());

            String code = line.taxClassCode() == null ? "UNCLASSIFIED" : line.taxClassCode();
            Accumulator accumulator =
                    byClass.computeIfAbsent(code, key -> new Accumulator(line.taxRate()));
            accumulator.add(line);
        }

        List<TaxClassTotal> breakdown = new ArrayList<>(byClass.size());
        byClass.forEach(
                (code, accumulator) ->
                        breakdown.add(
                                new TaxClassTotal(
                                        code,
                                        accumulator.rate,
                                        accumulator.net,
                                        accumulator.tax,
                                        accumulator.gross)));
        // Highest rate first, then by code: the standard rate is what a reader looks for.
        breakdown.sort(
                Comparator.comparing(TaxClassTotal::taxRate)
                        .reversed()
                        .thenComparing(TaxClassTotal::taxClassCode));

        return new SaleTotals(net, tax, discount, grand, List.copyOf(breakdown));
    }

    private static BigDecimal zero() {
        return BigDecimal.ZERO.setScale(MONEY_SCALE);
    }

    /** One tax class's running totals. */
    private static final class Accumulator {
        private final BigDecimal rate;
        private BigDecimal net = zero();
        private BigDecimal tax = zero();
        private BigDecimal gross = zero();

        private Accumulator(BigDecimal rate) {
            this.rate = rate == null ? BigDecimal.ZERO : rate;
        }

        private void add(PricedLine line) {
            net = net.add(line.netAmount());
            tax = tax.add(line.taxAmount());
            gross = gross.add(line.lineTotal());
        }
    }
}
