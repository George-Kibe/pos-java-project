package com.pos.common.money;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Currency;

import org.junit.jupiter.api.Test;

class MoneyTest {

    private static final Currency KES = Currency.getInstance("KES");
    private static final Currency USD = Currency.getInstance("USD");

    @Test
    void amountsAreNormalisedToTheCalculationScale() {
        assertThat(Money.of("10.5", "KES").amount()).isEqualByComparingTo("10.5000");
        assertThat(Money.of("10.5", "KES").amount().scale()).isEqualTo(4);
    }

    @Test
    void additionAndSubtractionAreExact() {
        Money a = Money.of("0.1", "KES");
        Money b = Money.of("0.2", "KES");
        // The case that silently fails in floating point: 0.1 + 0.2 != 0.3
        assertThat(a.add(b).amount()).isEqualByComparingTo("0.3000");
        assertThat(b.subtract(a).amount()).isEqualByComparingTo("0.1000");
    }

    @Test
    void repeatedAdditionDoesNotDrift() {
        Money total = Money.zero(KES);
        for (int i = 0; i < 1000; i++) {
            total = total.add(Money.of("0.01", "KES"));
        }
        assertThat(total.amount()).isEqualByComparingTo("10.0000");
    }

    @Test
    void multiplicationSupportsWeighedGoods() {
        Money perKg = Money.of("250.00", "KES");
        Money line = perKg.multiply(new BigDecimal("1.235"));
        assertThat(line.amount()).isEqualByComparingTo("308.7500");
    }

    @Test
    void roundingToTheMinorUnitIsHalfUp() {
        assertThat(Money.of("10.125", "KES").rounded().amount()).isEqualByComparingTo("10.13");
        assertThat(Money.of("10.124", "KES").rounded().amount()).isEqualByComparingTo("10.12");
    }

    @Test
    void mixingCurrenciesIsRejectedRatherThanSilentlyWrong() {
        Money kes = Money.of("100", "KES");
        Money usd = Money.of("100", "USD");

        assertThatThrownBy(() -> kes.add(usd))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("currency mismatch");
        assertThatThrownBy(() -> kes.compareTo(usd)).isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void signHelpersReadClearly() {
        assertThat(Money.zero(KES).isZero()).isTrue();
        assertThat(Money.of("-1", "KES").isNegative()).isTrue();
        assertThat(Money.of("1", "KES").isPositive()).isTrue();
        assertThat(Money.of("5", "KES").negate().amount()).isEqualByComparingTo("-5.0000");
    }

    @Test
    void comparisonOrdersByAmount() {
        assertThat(Money.of("5", "KES")).isLessThan(Money.of("10", "KES"));
        assertThat(Money.of("10", "KES")).isEqualByComparingTo(Money.of("10.0000", "KES"));
    }

    @Test
    void toStringCarriesTheCurrency() {
        assertThat(Money.of("1234.5", "USD")).hasToString("USD 1234.5000");
        assertThat(Money.of("1", "USD").currency()).isEqualTo(USD);
    }
}
