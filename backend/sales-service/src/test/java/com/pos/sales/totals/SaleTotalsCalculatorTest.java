package com.pos.sales.totals;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.sales.domain.totals.PricedLine;
import com.pos.sales.domain.totals.SaleTotals;
import com.pos.sales.domain.totals.SaleTotalsCalculator;
import com.pos.sales.domain.totals.TaxClassTotal;

/**
 * Adding a basket up.
 *
 * <p>The invariant that matters: the tax breakdown a receipt prints must sum to the sale's totals
 * exactly. A breakdown that is out by a cent is a receipt a customer can dispute and a VAT return
 * that does not reconcile.
 */
class SaleTotalsCalculatorTest {

    private static PricedLine line(
            String taxClass, String rate, String net, String tax, String discount, String total) {
        return new PricedLine(
                UUID.randomUUID(),
                "SKU-" + taxClass,
                BigDecimal.ONE,
                taxClass,
                new BigDecimal(rate),
                new BigDecimal(net),
                new BigDecimal(tax),
                new BigDecimal(discount),
                new BigDecimal(total));
    }

    @Test
    @DisplayName("a mixed basket groups by tax class and the breakdown sums to the total")
    void aMixedBasket() {
        // Standard-rated soap, zero-rated flour, and more soap.
        List<PricedLine> lines =
                List.of(
                        line("VAT16", "0.16", "100.0000", "16.0000", "0.0000", "116.0000"),
                        line("ZERO", "0.00", "120.0000", "0.0000", "0.0000", "120.0000"),
                        line("VAT16", "0.16", "50.0000", "8.0000", "0.0000", "58.0000"));

        SaleTotals totals = SaleTotalsCalculator.total(lines);

        assertThat(totals.net()).isEqualByComparingTo("270.0000");
        assertThat(totals.tax()).isEqualByComparingTo("24.0000");
        assertThat(totals.grand()).isEqualByComparingTo("294.0000");

        assertThat(totals.taxBreakdown()).hasSize(2);
        // Standard rate first: it is what a reader looks for.
        TaxClassTotal standard = totals.taxBreakdown().getFirst();
        assertThat(standard.taxClassCode()).isEqualTo("VAT16");
        assertThat(standard.net()).isEqualByComparingTo("150.0000");
        assertThat(standard.tax()).isEqualByComparingTo("24.0000");
        assertThat(standard.gross()).isEqualByComparingTo("174.0000");

        TaxClassTotal zero = totals.taxBreakdown().get(1);
        assertThat(zero.taxClassCode()).isEqualTo("ZERO");
        assertThat(zero.tax()).isEqualByComparingTo("0");

        // The invariant: the rows add up to the sale.
        assertThat(sumOf(totals, TaxClassTotal::net)).isEqualByComparingTo(totals.net());
        assertThat(sumOf(totals, TaxClassTotal::tax)).isEqualByComparingTo(totals.tax());
        assertThat(sumOf(totals, TaxClassTotal::gross)).isEqualByComparingTo(totals.grand());
    }

    @Test
    @DisplayName("nothing is recalculated: an odd per-line tax figure is carried through verbatim")
    void catalogsFiguresAreNotSecondGuessed() {
        // 16% of 99.99 is 15.9984, which catalog rounded to 15.9984 on an inclusive price. If this
        // class recomputed tax from the rate it would disagree, and the receipt would not match.
        List<PricedLine> lines =
                List.of(line("VAT16", "0.16", "86.2000", "13.7900", "0.0000", "99.9900"));

        SaleTotals totals = SaleTotalsCalculator.total(lines);

        assertThat(totals.tax()).isEqualByComparingTo("13.7900");
        assertThat(totals.net()).isEqualByComparingTo("86.2000");
        assertThat(totals.grand()).isEqualByComparingTo("99.9900");
        // Deliberately not 86.20 * 0.16 = 13.792.
        assertThat(totals.tax()).isNotEqualByComparingTo("13.7920");
    }

    @Test
    @DisplayName("a thousand awkward lines still sum exactly")
    void noDriftAcrossManyLines() {
        List<PricedLine> lines =
                java.util.stream.IntStream.range(0, 1000)
                        .mapToObj(
                                i ->
                                        line(
                                                i % 2 == 0 ? "VAT16" : "ZERO",
                                                i % 2 == 0 ? "0.16" : "0.00",
                                                "8.6207",
                                                i % 2 == 0 ? "1.3793" : "0.0000",
                                                "0.0000",
                                                i % 2 == 0 ? "10.0000" : "8.6207"))
                        .toList();

        SaleTotals totals = SaleTotalsCalculator.total(lines);

        assertThat(sumOf(totals, TaxClassTotal::net)).isEqualByComparingTo(totals.net());
        assertThat(sumOf(totals, TaxClassTotal::tax)).isEqualByComparingTo(totals.tax());
        assertThat(sumOf(totals, TaxClassTotal::gross)).isEqualByComparingTo(totals.grand());
        assertThat(totals.grand()).isEqualByComparingTo("9310.3500");
    }

    @Test
    void discountsAreTotalledSeparatelyFromTheNet() {
        List<PricedLine> lines =
                List.of(
                        line("VAT16", "0.16", "86.2069", "13.7931", "20.0000", "100.0000"),
                        line("VAT16", "0.16", "43.1034", "6.8966", "0.0000", "50.0000"));

        SaleTotals totals = SaleTotalsCalculator.total(lines);

        assertThat(totals.discount()).isEqualByComparingTo("20.0000");
        // The discount is already inside the net and the line total; it is reported, not subtracted
        // again.
        assertThat(totals.grand()).isEqualByComparingTo("150.0000");
    }

    @Test
    @DisplayName("an empty basket totals zero rather than throwing")
    void anEmptyBasket() {
        SaleTotals totals = SaleTotalsCalculator.total(List.of());

        assertThat(totals.grand()).isEqualByComparingTo("0");
        assertThat(totals.taxBreakdown()).isEmpty();
    }

    @Test
    @DisplayName("a line with no tax class is grouped rather than dropped")
    void anUnclassifiedLineIsStillCounted() {
        List<PricedLine> lines =
                List.of(
                        new PricedLine(
                                UUID.randomUUID(),
                                "SKU-1",
                                BigDecimal.ONE,
                                null,
                                null,
                                new BigDecimal("10.0000"),
                                BigDecimal.ZERO,
                                BigDecimal.ZERO,
                                new BigDecimal("10.0000")));

        SaleTotals totals = SaleTotalsCalculator.total(lines);

        assertThat(totals.grand()).isEqualByComparingTo("10.0000");
        // Visible as UNCLASSIFIED, because a line silently missing from the breakdown is how a
        // receipt comes to disagree with its own total.
        assertThat(totals.taxBreakdown()).hasSize(1);
        assertThat(totals.taxBreakdown().getFirst().taxClassCode()).isEqualTo("UNCLASSIFIED");
    }

    @Test
    void theBreakdownOrderIsStableForTheSameBasketInAnyOrder() {
        List<PricedLine> ascending =
                List.of(
                        line("ZERO", "0.00", "100.0000", "0.0000", "0.0000", "100.0000"),
                        line("VAT8", "0.08", "100.0000", "8.0000", "0.0000", "108.0000"),
                        line("VAT16", "0.16", "100.0000", "16.0000", "0.0000", "116.0000"));
        List<PricedLine> shuffled = List.of(ascending.get(2), ascending.get(0), ascending.get(1));

        assertThat(
                        SaleTotalsCalculator.total(ascending).taxBreakdown().stream()
                                .map(TaxClassTotal::taxClassCode)
                                .toList())
                .containsExactly("VAT16", "VAT8", "ZERO")
                .isEqualTo(
                        SaleTotalsCalculator.total(shuffled).taxBreakdown().stream()
                                .map(TaxClassTotal::taxClassCode)
                                .toList());
    }

    @Test
    @DisplayName("a client's total is compared, not trusted")
    void aClientTotalIsCheckedAgainstTheServers() {
        SaleTotals totals =
                SaleTotalsCalculator.total(
                        List.of(
                                line(
                                        "VAT16",
                                        "0.16",
                                        "100.0000",
                                        "16.0000",
                                        "0.0000",
                                        "116.0000")));

        assertThat(totals.agreesWith(new BigDecimal("116.00"))).isTrue();
        assertThat(totals.agreesWith(new BigDecimal("110.00"))).isFalse();
        assertThat(totals.agreesWith(null)).isFalse();

        // An offline till holding a stale price claimed less than is owed.
        assertThat(totals.varianceAgainst(new BigDecimal("110.00")))
                .isEqualByComparingTo("-6.0000");
        assertThat(totals.varianceAgainst(null)).isEqualByComparingTo("0");
    }

    private static BigDecimal sumOf(
            SaleTotals totals, java.util.function.Function<TaxClassTotal, BigDecimal> field) {
        return totals.taxBreakdown().stream().map(field).reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
