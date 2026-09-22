package com.pos.sales.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.sales.domain.policy.ChangeCalculator;
import com.pos.sales.domain.policy.ChangeDue;

/** Change due, and the notes to count out. */
class ChangeCalculatorTest {

    @Test
    @DisplayName("change is broken into the fewest notes, largest first")
    void theDenominationBreakdown() {
        ChangeDue change =
                ChangeCalculator.calculate(new BigDecimal("1265.00"), new BigDecimal("2000.00"));

        assertThat(change.amount()).isEqualByComparingTo("735.00");
        assertThat(change.denominations())
                .extracting(ChangeDue.DenominationCount::denomination)
                .containsExactly(
                        new BigDecimal("500"),
                        new BigDecimal("200"),
                        new BigDecimal("20"),
                        new BigDecimal("10"),
                        new BigDecimal("5"));
        assertThat(change.unpayableRemainder()).isEqualByComparingTo("0");
    }

    @Test
    void exactMoneyNeedsNoChange() {
        ChangeDue change =
                ChangeCalculator.calculate(new BigDecimal("450.00"), new BigDecimal("450.00"));

        assertThat(change.isExact()).isTrue();
        assertThat(change.denominations()).isEmpty();
    }

    @Test
    @DisplayName("multiple notes of the same denomination are counted, not repeated")
    void repeatedDenominations() {
        ChangeDue change =
                ChangeCalculator.calculate(new BigDecimal("100.00"), new BigDecimal("3100.00"));

        assertThat(change.amount()).isEqualByComparingTo("3000.00");
        assertThat(change.denominations()).hasSize(1);
        assertThat(change.denominations().getFirst().denomination()).isEqualByComparingTo("1000");
        assertThat(change.denominations().getFirst().count()).isEqualTo(3);
    }

    @Test
    @DisplayName("cents that no coin can pay are reported, not quietly kept")
    void anUnpayableRemainderIsVisible() {
        // The smallest coin is 1 shilling, so 40 cents cannot be handed over.
        ChangeDue change =
                ChangeCalculator.calculate(new BigDecimal("99.60"), new BigDecimal("100.00"));

        assertThat(change.amount()).isEqualByComparingTo("0.40");
        assertThat(change.denominations()).isEmpty();
        // Surfaced so the lane can round openly rather than the shop pocketing it silently.
        assertThat(change.unpayableRemainder()).isEqualByComparingTo("0.40");
    }

    @Test
    void aTillNeverHandsOutNegativeChange() {
        assertThatThrownBy(
                        () ->
                                ChangeCalculator.calculate(
                                        new BigDecimal("500.00"), new BigDecimal("400.00")))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("does not cover");
    }

    @Test
    void refusesMissingOrNegativeAmounts() {
        assertThatThrownBy(() -> ChangeCalculator.calculate(null, BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> ChangeCalculator.calculate(BigDecimal.TEN, null))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> ChangeCalculator.calculate(new BigDecimal("-1.00"), BigDecimal.TEN))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("negative");
    }

    @Test
    @DisplayName("a shop with its own denominations gets its own breakdown")
    void customDenominations() {
        ChangeDue change =
                ChangeCalculator.calculate(
                        new BigDecimal("10.00"),
                        new BigDecimal("100.00"),
                        List.of(new BigDecimal("25"), new BigDecimal("10"), new BigDecimal("1")));

        assertThat(change.amount()).isEqualByComparingTo("90.00");
        assertThat(change.denominations())
                .extracting(ChangeDue.DenominationCount::denomination)
                .containsExactly(new BigDecimal("25"), new BigDecimal("10"), new BigDecimal("1"));
        // 3x25 + 1x10 + 5x1 = 90, and the denominations arrive unsorted-safe.
        assertThat(change.unpayableRemainder()).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("the breakdown always adds back up to the change due")
    void theBreakdownReconciles() {
        for (int shillings = 1; shillings <= 2000; shillings += 7) {
            BigDecimal tendered = new BigDecimal(shillings + 3000);
            ChangeDue change = ChangeCalculator.calculate(new BigDecimal("3000"), tendered);

            BigDecimal counted =
                    change.denominations().stream()
                            .map(d -> d.denomination().multiply(new BigDecimal(d.count())))
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .add(change.unpayableRemainder());

            assertThat(counted).as("change for %s", tendered).isEqualByComparingTo(change.amount());
        }
    }
}
