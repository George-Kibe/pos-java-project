package com.pos.purchasing.matching;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.pos.purchasing.domain.matching.LineVariance;
import com.pos.purchasing.domain.matching.MatchResult;
import com.pos.purchasing.domain.matching.MatchTolerance;
import com.pos.purchasing.domain.matching.MatchVerdict;
import com.pos.purchasing.domain.matching.MatchableLine;
import com.pos.purchasing.domain.matching.ThreeWayMatcher;
import com.pos.purchasing.domain.matching.VarianceType;

/**
 * Should we pay this invoice?
 *
 * <p>The cases that matter are the ones where the total looks right. A supplier who bills the
 * agreed amount while charging for goods that never arrived passes any check that only compares
 * grand totals, which is exactly why the comparison is per product.
 */
class ThreeWayMatcherTest {

    private static final UUID FLOUR = UUID.randomUUID();
    private static final UUID SUGAR = UUID.randomUUID();

    private static MatchableLine line(UUID product, String quantity, String unitCost) {
        return new MatchableLine(
                product,
                product == FLOUR ? "FLOUR-2KG" : "SUGAR-1KG",
                new BigDecimal(quantity),
                new BigDecimal(unitCost));
    }

    private static final MatchTolerance TWO_PERCENT_OR_TEN =
            new MatchTolerance(new BigDecimal("10.00"), new BigDecimal("0.02"));

    @Nested
    class CleanMatches {

        @Test
        @DisplayName("ordered, received and invoiced all agree")
        void everythingAgrees() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "95.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, MatchTolerance.exact());

            assertThat(result.verdict()).isEqualTo(MatchVerdict.MATCHED);
            assertThat(result.variance()).isEqualByComparingTo("0");
            assertThat(result.variances()).isEmpty();
            assertThat(result.isPayable()).isTrue();
            assertThat(result.isOverBilled()).isFalse();
        }

        @Test
        @DisplayName("a short delivery is paid for at the short quantity, not the ordered one")
        void aShortDeliveryInvoicedShortIsClean() {
            // 100 ordered, 80 turned up, 80 billed. Nothing is wrong with that.
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "80", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "80", "95.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, MatchTolerance.exact());

            assertThat(result.verdict()).isEqualTo(MatchVerdict.MATCHED);
            assertThat(result.justifiedTotal()).isEqualByComparingTo("7600.00");
        }
    }

    @Nested
    class OverBilling {

        @Test
        @DisplayName("a price above the agreed one is flagged with what it costs")
        void billedAboveTheAgreedPrice() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            // Quietly up by five shillings a unit.
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "100.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXCEPTION);
            assertThat(result.isOverBilled()).isTrue();
            assertThat(result.isPayable()).isFalse();
            assertThat(result.variance()).isEqualByComparingTo("500.00");

            LineVariance finding = result.variances().getFirst();
            assertThat(finding.type()).isEqualTo(VarianceType.PRICE_VARIANCE);
            assertThat(finding.expected()).isEqualByComparingTo("95.00");
            assertThat(finding.actual()).isEqualByComparingTo("100.00");
            assertThat(finding.amountEffect()).isEqualByComparingTo("500.00");
        }

        @Test
        @DisplayName("billed for more units than arrived")
        void billedForMoreThanWasReceived() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "80", "95.00"));
            // Billed the full order although only 80 arrived.
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "95.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXCEPTION);
            assertThat(result.variance()).isEqualByComparingTo("1900.00");
            assertThat(result.variances())
                    .extracting(LineVariance::type)
                    .containsExactly(VarianceType.QUANTITY_VARIANCE);
        }

        @Test
        @DisplayName("the grand total is right but the goods were never delivered")
        void theTotalMatchesWhileTheGoodsDoNot() {
            // Ordered and received flour only. The invoice bills sugar that never came, and
            // under-bills the flour by exactly the same amount, so the total is correct.
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "100.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "100", "100.00"));
            List<MatchableLine> invoiced =
                    List.of(line(FLOUR, "90", "100.00"), line(SUGAR, "100", "10.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            // A total-only check would have passed this.
            assertThat(result.invoicedTotal()).isEqualByComparingTo(result.justifiedTotal());
            assertThat(result.variance()).isEqualByComparingTo("0");
            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXCEPTION);
            assertThat(result.variances())
                    .extracting(LineVariance::type)
                    .contains(VarianceType.NOT_RECEIVED, VarianceType.QUANTITY_VARIANCE);
        }
    }

    @Nested
    class Tolerance {

        @Test
        @DisplayName("a few cents of price drift is paid rather than chased")
        void smallPriceDriftIsTolerated() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "95.05"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.verdict()).isEqualTo(MatchVerdict.WITHIN_TOLERANCE);
            assertThat(result.isPayable()).isTrue();
            // Still reported: tolerated is not the same as unnoticed.
            assertThat(result.variances())
                    .extracting(LineVariance::type)
                    .containsExactly(VarianceType.PRICE_VARIANCE);
        }

        @Test
        @DisplayName("the absolute floor covers a small invoice a percentage would reject")
        void theAbsoluteFloorApplies() {
            List<MatchableLine> ordered = List.of(line(SUGAR, "1", "20.00"));
            List<MatchableLine> received = List.of(line(SUGAR, "1", "20.00"));
            List<MatchableLine> invoiced = List.of(line(SUGAR, "1", "28.00"));

            // 8.00 on 20.00 is 40% - far beyond the percentage, but inside the 10.00 floor.
            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.verdict()).isEqualTo(MatchVerdict.WITHIN_TOLERANCE);
        }

        @Test
        @DisplayName("the percentage covers a large invoice the floor would reject")
        void thePercentageApplies() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "10000", "100.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "10000", "100.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "10000", "101.00"));

            // 10,000 on a million: way past the 10.00 floor, inside two percent.
            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.verdict()).isEqualTo(MatchVerdict.WITHIN_TOLERANCE);
            assertThat(result.variance()).isEqualByComparingTo("10000.00");
        }

        @Test
        @DisplayName("a quantity variance is never tolerated, however small the money")
        void aQuantityVarianceIsStructural() {
            List<MatchableLine> ordered = List.of(line(SUGAR, "100", "0.10"));
            List<MatchableLine> received = List.of(line(SUGAR, "100", "0.10"));
            // Billed for one extra unit: ten cents, and still wrong.
            List<MatchableLine> invoiced = List.of(line(SUGAR, "101", "0.10"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.variance()).isEqualByComparingTo("0.10");
            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXCEPTION);
        }

        @Test
        void anExactToleranceAllowsNothing() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "95.01"));

            assertThat(
                            ThreeWayMatcher.match(
                                            ordered, received, invoiced, MatchTolerance.exact())
                                    .verdict())
                    .isEqualTo(MatchVerdict.EXCEPTION);
        }

        @Test
        void aMissingToleranceIsTreatedAsExact() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "95.01"));

            assertThat(ThreeWayMatcher.match(ordered, received, invoiced, null).verdict())
                    .isEqualTo(MatchVerdict.EXCEPTION);
        }
    }

    @Nested
    class StructuralFindings {

        @Test
        @DisplayName("more received than ordered is flagged even when billed correctly")
        void overReceipt() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            // 120 turned up and the supplier billed all 120 at the agreed price.
            List<MatchableLine> received = List.of(line(FLOUR, "120", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "120", "95.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            // Arithmetically consistent, but nobody authorised the extra 20.
            assertThat(result.variance()).isEqualByComparingTo("0");
            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXCEPTION);
            LineVariance finding = result.variances().getFirst();
            assertThat(finding.type()).isEqualTo(VarianceType.OVER_RECEIPT);
            assertThat(finding.amountEffect()).isEqualByComparingTo("1900.00");
        }

        @Test
        @DisplayName("received but not invoiced is reported without blocking payment")
        void notInvoiced() {
            List<MatchableLine> ordered =
                    List.of(line(FLOUR, "100", "95.00"), line(SUGAR, "50", "40.00"));
            List<MatchableLine> received =
                    List.of(line(FLOUR, "100", "95.00"), line(SUGAR, "50", "40.00"));
            // Only the flour is on this invoice; the sugar will be billed separately.
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "95.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.variances())
                    .extracting(LineVariance::type)
                    .containsExactly(VarianceType.NOT_INVOICED);
            // The bill for what did arrive is short by the sugar, which is expected, so the
            // shortfall itself must not be treated as an overcharge.
            assertThat(result.isOverBilled()).isFalse();
        }

        @Test
        @DisplayName("a delivery with no purchase order takes the received price as agreed")
        void noPurchaseOrderToCompareWith() {
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "95.00"));

            MatchResult result =
                    ThreeWayMatcher.match(List.of(), received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.verdict()).isEqualTo(MatchVerdict.MATCHED);
            assertThat(result.justifiedTotal()).isEqualByComparingTo("9500.00");
        }

        @Test
        @DisplayName("without an order, a price above the receipt is still caught")
        void withoutAnOrderThePriceIsCheckedAgainstTheReceipt() {
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> invoiced = List.of(line(FLOUR, "100", "120.00"));

            MatchResult result =
                    ThreeWayMatcher.match(List.of(), received, invoiced, TWO_PERCENT_OR_TEN);

            assertThat(result.verdict()).isEqualTo(MatchVerdict.EXCEPTION);
            assertThat(result.variances().getFirst().description()).contains("receipt");
        }

        @Test
        @DisplayName("a product split across two invoice lines is summed, not refused")
        void aProductAppearingTwiceIsSummed() {
            List<MatchableLine> ordered = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> received = List.of(line(FLOUR, "100", "95.00"));
            List<MatchableLine> invoiced =
                    List.of(line(FLOUR, "60", "95.00"), line(FLOUR, "40", "95.00"));

            MatchResult result =
                    ThreeWayMatcher.match(ordered, received, invoiced, MatchTolerance.exact());

            assertThat(result.verdict()).isEqualTo(MatchVerdict.MATCHED);
            assertThat(result.invoicedTotal()).isEqualByComparingTo("9500.00");
        }
    }

    @Nested
    class ToleranceType {

        @Test
        void theMoreGenerousLimitWins() {
            MatchTolerance tolerance =
                    new MatchTolerance(new BigDecimal("10.00"), new BigDecimal("0.02"));

            // Small total: the floor is the larger allowance.
            assertThat(tolerance.allowedOn(new BigDecimal("100.00"))).isEqualByComparingTo("10.00");
            // Large total: the percentage is.
            assertThat(tolerance.allowedOn(new BigDecimal("100000.00")))
                    .isEqualByComparingTo("2000.00");
        }

        @Test
        void exactAllowsNothing() {
            assertThat(MatchTolerance.exact().allowedOn(new BigDecimal("100000.00")))
                    .isEqualByComparingTo("0");
        }
    }
}
