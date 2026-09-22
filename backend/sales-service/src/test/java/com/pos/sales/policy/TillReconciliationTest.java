package com.pos.sales.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.sales.domain.policy.TillReconciliation;

/** What should be in the drawer at close. */
class TillReconciliationTest {

    private static TillReconciliation of(
            String openingFloat, String sales, String refunds, String drops, String counted) {
        return TillReconciliation.of(
                new BigDecimal(openingFloat),
                new BigDecimal(sales),
                new BigDecimal(refunds),
                new BigDecimal(drops),
                new BigDecimal(counted));
    }

    @Test
    @DisplayName("float plus takings, less refunds and drops, is what should be there")
    void theExpectedAmount() {
        TillReconciliation reconciliation =
                of("5000.00", "42000.00", "1500.00", "20000.00", "25500.00");

        assertThat(reconciliation.expectedCash()).isEqualByComparingTo("25500.00");
        assertThat(reconciliation.balances()).isTrue();
        assertThat(reconciliation.variance()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName(
            "a drop to the safe is the difference between reconciling and being thousands over")
    void dropsAreSubtracted() {
        TillReconciliation withoutDrop = of("5000.00", "42000.00", "0.00", "0.00", "27000.00");
        TillReconciliation withDrop = of("5000.00", "42000.00", "0.00", "20000.00", "27000.00");

        // The same counted cash reads as 20,000 missing if the drop is not accounted for, and as
        // a balanced drawer if it is.
        assertThat(withoutDrop.variance()).isEqualByComparingTo("-20000.00");
        assertThat(withDrop.variance()).isEqualByComparingTo("0");
        assertThat(withDrop.balances()).isTrue();
    }

    @Test
    void aShortDrawerIsShort() {
        TillReconciliation reconciliation = of("5000.00", "10000.00", "0.00", "0.00", "14500.00");

        assertThat(reconciliation.isShort()).isTrue();
        assertThat(reconciliation.isOver()).isFalse();
        assertThat(reconciliation.variance()).isEqualByComparingTo("-500.00");
    }

    @Test
    void anOverDrawerIsOver() {
        TillReconciliation reconciliation = of("5000.00", "10000.00", "0.00", "0.00", "15100.00");

        assertThat(reconciliation.isOver()).isTrue();
        assertThat(reconciliation.isShort()).isFalse();
        assertThat(reconciliation.variance()).isEqualByComparingTo("100.00");
    }

    @Test
    @DisplayName("refunds paid out of the drawer reduce what should be in it")
    void refundsAreSubtracted() {
        TillReconciliation reconciliation = of("5000.00", "10000.00", "750.00", "0.00", "14250.00");

        assertThat(reconciliation.expectedCash()).isEqualByComparingTo("14250.00");
        assertThat(reconciliation.balances()).isTrue();
    }

    @Test
    void anUncountedDrawerCountsAsZeroRatherThanThrowing() {
        TillReconciliation reconciliation =
                TillReconciliation.of(
                        new BigDecimal("5000.00"),
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        BigDecimal.ZERO,
                        null);

        assertThat(reconciliation.countedCash()).isEqualByComparingTo("0");
        assertThat(reconciliation.variance()).isEqualByComparingTo("-5000.00");
    }
}
