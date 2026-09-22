package com.pos.reporting.domain.policy;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

/**
 * A shift's takings, worked out the way the till works them out.
 *
 * <p>Reconciling "to the cent" only means something if both sides do the same sum, so this is the
 * till's arithmetic, stated once: a sale adds its cash (net of change) to cash sales and the rest
 * to non-cash; a void puts its cash back out as a refund; a cash refund paid from this shift adds
 * to cash refunds, whichever shift took the sale. Expected cash is the float plus cash sales, less
 * refunds and drops.
 *
 * <p>Pure: no Spring, no JPA.
 */
public final class ShiftArithmetic {

    private ShiftArithmetic() {}

    public record Tender(String method, BigDecimal amount) {}

    /** A sale taken on the shift. */
    public record Sale(BigDecimal grandTotal, List<Tender> tenders, boolean voided) {
        BigDecimal cash() {
            return tenders.stream()
                    .filter(tender -> "CASH".equals(tender.method()))
                    .map(Tender::amount)
                    .reduce(BigDecimal.ZERO, BigDecimal::add);
        }
    }

    /** A refund paid from the shift's drawer, or through a provider while it was open. */
    public record Refund(String method, BigDecimal amount) {}

    /** What the till itself said at close. */
    public record TillFigures(
            BigDecimal openingFloat,
            BigDecimal cashSales,
            BigDecimal cashRefunds,
            BigDecimal cashDrops,
            BigDecimal nonCashSales,
            int saleCount,
            BigDecimal expectedCash,
            BigDecimal countedCash,
            BigDecimal variance) {}

    public record Totals(
            int saleCount,
            int voidCount,
            BigDecimal grossSales,
            BigDecimal cashSales,
            BigDecimal nonCashSales,
            BigDecimal cashRefunds,
            BigDecimal nonCashRefunds,
            Map<String, BigDecimal> takingsByMethod) {}

    /** A figure where the two sides disagree, and by how much. */
    public record Difference(String figure, BigDecimal ours, BigDecimal till) {
        public BigDecimal amount() {
            return ours.subtract(till);
        }
    }

    public static Totals totals(List<Sale> sales, List<Refund> refunds) {
        BigDecimal gross = BigDecimal.ZERO;
        BigDecimal cashSales = BigDecimal.ZERO;
        BigDecimal nonCash = BigDecimal.ZERO;
        BigDecimal cashRefunds = BigDecimal.ZERO;
        BigDecimal nonCashRefunds = BigDecimal.ZERO;
        int voids = 0;
        Map<String, BigDecimal> byMethod = new TreeMap<>();

        for (Sale sale : sales) {
            BigDecimal cash = sale.cash();
            gross = gross.add(sale.grandTotal());
            cashSales = cashSales.add(cash);
            nonCash = nonCash.add(sale.grandTotal().subtract(cash));
            for (Tender tender : sale.tenders()) {
                byMethod.merge(tender.method(), tender.amount(), BigDecimal::add);
            }
            if (sale.voided()) {
                voids++;
                // The till books a void as its cash going back out, not as un-selling it.
                cashRefunds = cashRefunds.add(cash);
            }
        }
        for (Refund refund : refunds) {
            if ("CASH".equals(refund.method())) {
                cashRefunds = cashRefunds.add(refund.amount());
            } else {
                nonCashRefunds = nonCashRefunds.add(refund.amount());
            }
        }
        return new Totals(
                sales.size(),
                voids,
                gross,
                cashSales,
                nonCash,
                cashRefunds,
                nonCashRefunds,
                byMethod);
    }

    public static BigDecimal expectedCash(
            BigDecimal openingFloat, Totals totals, BigDecimal cashDrops) {
        return openingFloat
                .add(totals.cashSales())
                .subtract(totals.cashRefunds())
                .subtract(cashDrops);
    }

    /** Every figure the till reported that ours does not match, to the cent. */
    public static List<Difference> reconcile(Totals ours, TillFigures till) {
        List<Difference> differences = new java.util.ArrayList<>();
        compare(differences, "cashSales", ours.cashSales(), till.cashSales());
        compare(differences, "cashRefunds", ours.cashRefunds(), till.cashRefunds());
        compare(differences, "nonCashSales", ours.nonCashSales(), till.nonCashSales());
        compare(
                differences,
                "saleCount",
                BigDecimal.valueOf(ours.saleCount()),
                BigDecimal.valueOf(till.saleCount()));
        compare(
                differences,
                "expectedCash",
                expectedCash(till.openingFloat(), ours, till.cashDrops()),
                till.expectedCash());
        return List.copyOf(differences);
    }

    private static void compare(
            List<Difference> into, String figure, BigDecimal ours, BigDecimal till) {
        if (ours.compareTo(till) != 0) {
            into.add(new Difference(figure, ours, till));
        }
    }
}
