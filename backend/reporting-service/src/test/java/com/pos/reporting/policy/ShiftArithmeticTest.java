package com.pos.reporting.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.reporting.domain.policy.ShiftArithmetic;
import com.pos.reporting.domain.policy.ShiftArithmetic.Refund;
import com.pos.reporting.domain.policy.ShiftArithmetic.Sale;
import com.pos.reporting.domain.policy.ShiftArithmetic.Tender;
import com.pos.reporting.domain.policy.ShiftArithmetic.TillFigures;

/** The till's arithmetic, stated once, and checked against what the till said. */
class ShiftArithmeticTest {

    private static final Sale CASH_SALE =
            new Sale(money("348.00"), List.of(new Tender("CASH", money("348.00"))), false);
    private static final Sale SPLIT_SALE =
            new Sale(
                    money("200.00"),
                    List.of(
                            new Tender("CASH", money("50.00")),
                            new Tender("MPESA", money("150.00"))),
                    false);
    private static final Sale VOIDED_SALE =
            new Sale(money("116.00"), List.of(new Tender("CASH", money("116.00"))), true);

    @Test
    @DisplayName("sales split into cash and the rest, and a void goes back out as a cash refund")
    void theTillsArithmetic() {
        ShiftArithmetic.Totals totals =
                ShiftArithmetic.totals(
                        List.of(CASH_SALE, SPLIT_SALE, VOIDED_SALE),
                        List.of(
                                new Refund("CASH", money("20.00")),
                                new Refund("CARD", money("30.00"))));

        assertThat(totals.saleCount()).isEqualTo(3);
        assertThat(totals.voidCount()).isEqualTo(1);
        assertThat(totals.grossSales()).isEqualByComparingTo("664.00");
        assertThat(totals.cashSales()).isEqualByComparingTo("514.00");
        assertThat(totals.nonCashSales()).isEqualByComparingTo("150.00");
        // The voided sale's cash, plus the cash refund. The card refund touched no drawer.
        assertThat(totals.cashRefunds()).isEqualByComparingTo("136.00");
        assertThat(totals.nonCashRefunds()).isEqualByComparingTo("30.00");
        assertThat(totals.takingsByMethod())
                .containsEntry("CASH", money("514.00"))
                .containsEntry("MPESA", money("150.00"));
    }

    @Test
    void expectedCashIsFloatPlusSalesLessRefundsAndDrops() {
        ShiftArithmetic.Totals totals =
                ShiftArithmetic.totals(
                        List.of(CASH_SALE), List.of(new Refund("CASH", money("116.00"))));

        assertThat(ShiftArithmetic.expectedCash(money("5000.00"), totals, money("1000.00")))
                .isEqualByComparingTo("4232.00");
    }

    @Test
    @DisplayName("figures that agree with the till to the cent reconcile")
    void agreementReconciles() {
        ShiftArithmetic.Totals totals = ShiftArithmetic.totals(List.of(CASH_SALE), List.of());
        TillFigures till =
                new TillFigures(
                        money("1000.00"),
                        money("348.00"),
                        money("0"),
                        money("0"),
                        money("0"),
                        1,
                        money("1348.00"),
                        money("1348.00"),
                        money("0"));

        assertThat(ShiftArithmetic.reconcile(totals, till)).isEmpty();
    }

    @Test
    @DisplayName("a missing sale is named, figure by figure, not rounded away")
    void aDisagreementIsListedWithItsAmount() {
        ShiftArithmetic.Totals totals = ShiftArithmetic.totals(List.of(CASH_SALE), List.of());
        TillFigures till =
                new TillFigures(
                        money("1000.00"),
                        money("348.01"),
                        money("0"),
                        money("0"),
                        money("0"),
                        2,
                        money("1348.01"),
                        money("1348.01"),
                        money("0"));

        List<ShiftArithmetic.Difference> differences = ShiftArithmetic.reconcile(totals, till);

        assertThat(differences)
                .extracting(ShiftArithmetic.Difference::figure)
                .containsExactly("cashSales", "saleCount", "expectedCash");
        assertThat(differences.getFirst().amount()).isEqualByComparingTo("-0.01");
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
