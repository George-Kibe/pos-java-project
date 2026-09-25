package com.pos.catalog.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.catalog.domain.pricing.MarginCheck;
import com.pos.catalog.domain.pricing.MarginCheck.Status;

@DisplayName("Margin against cost")
class MarginCheckTest {

    private static final BigDecimal VAT = new BigDecimal("0.16");
    private static final BigDecimal TARGET = new BigDecimal("0.15");

    private static BigDecimal d(String value) {
        return new BigDecimal(value);
    }

    @Test
    @DisplayName("a VAT-inclusive price is compared without its VAT")
    void comparesPriceWithoutTax() {
        MarginCheck check = MarginCheck.of(d("150"), d("232"), true, VAT, TARGET);

        assertThat(check.netPrice()).isEqualByComparingTo("200");
        assertThat(check.margin()).isEqualByComparingTo("0.25");
        assertThat(check.status()).isEqualTo(Status.OK);
        assertThat(check.suggestedPrice()).isNull();
    }

    @Test
    @DisplayName("a cost that squeezes the margin suggests a price, with VAT, rounded up")
    void belowTargetSuggestsAPrice() {
        MarginCheck check = MarginCheck.of(d("190"), d("232"), true, VAT, TARGET);

        assertThat(check.margin()).isEqualByComparingTo("0.05");
        assertThat(check.status()).isEqualTo(Status.BELOW_TARGET);
        // 190 / 0.85 = 223.53 without VAT, 259.29 with it: up to 260, never down to 259.
        assertThat(check.suggestedPrice()).isEqualByComparingTo("260");
        BigDecimal earned =
                MarginCheck.of(d("190"), check.suggestedPrice(), true, VAT, TARGET).margin();
        assertThat(earned).isGreaterThanOrEqualTo(TARGET);
    }

    @Test
    @DisplayName("a price entered without VAT is suggested without VAT")
    void exclusivePriceSuggestsExclusive() {
        MarginCheck check = MarginCheck.of(d("190"), d("200"), false, VAT, TARGET);

        assertThat(check.netPrice()).isEqualByComparingTo("200");
        assertThat(check.suggestedPrice()).isEqualByComparingTo("224");
    }

    @Test
    @DisplayName("selling below cost is flagged even with no target, and the cost is suggested")
    void belowCostWithoutTarget() {
        MarginCheck check = MarginCheck.of(d("210"), d("232"), true, VAT, null);

        assertThat(check.status()).isEqualTo(Status.BELOW_COST);
        assertThat(check.margin()).isNegative();
        // Break-even: 210 plus VAT is 243.60, up to 244.
        assertThat(check.suggestedPrice()).isEqualByComparingTo("244");
    }

    @Test
    @DisplayName("with no target and a price above cost there is nothing to say")
    void noTarget() {
        MarginCheck check = MarginCheck.of(d("150"), d("232"), true, VAT, null);

        assertThat(check.status()).isEqualTo(Status.NO_TARGET);
        assertThat(check.suggestedPrice()).isNull();
    }

    @Test
    @DisplayName("a zero-rated item has no VAT to take out")
    void zeroRated() {
        MarginCheck check = MarginCheck.of(d("100"), d("110"), true, BigDecimal.ZERO, TARGET);

        assertThat(check.netPrice()).isEqualByComparingTo("110");
        assertThat(check.status()).isEqualTo(Status.BELOW_TARGET);
        assertThat(check.suggestedPrice()).isEqualByComparingTo("118");
    }

    @Test
    @DisplayName("an item given away has no margin and is below cost")
    void zeroPrice() {
        MarginCheck check = MarginCheck.of(d("10"), BigDecimal.ZERO, true, VAT, TARGET);

        assertThat(check.margin()).isNull();
        assertThat(check.status()).isEqualTo(Status.BELOW_COST);
    }

    @Test
    void takesVatOutOfAnInvoicedCost() {
        assertThat(MarginCheck.withoutTax(d("174"), VAT)).isEqualByComparingTo("150");
    }

    @Test
    void aTargetOfAHundredPercentIsRefused() {
        assertThatThrownBy(() -> MarginCheck.of(d("1"), d("2"), true, VAT, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
