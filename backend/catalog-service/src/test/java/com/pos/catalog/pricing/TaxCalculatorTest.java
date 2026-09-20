package com.pos.catalog.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.pos.catalog.domain.pricing.TaxCalculation;
import com.pos.catalog.domain.pricing.TaxCalculator;
import com.pos.common.money.Money;

class TaxCalculatorTest {

    private static final BigDecimal VAT_16 = new BigDecimal("0.16");
    private static final BigDecimal ZERO = BigDecimal.ZERO;

    @Test
    @DisplayName("tax is extracted from an inclusive price, not added on top of it")
    void extractsTaxFromAnInclusivePrice() {
        // 116 inclusive of 16% is 100 net plus 16 tax. The naive gross x rate gives 18.56, which
        // would overcharge the customer on every single line.
        TaxCalculation result = TaxCalculator.extractFrom(Money.of("116.00", "KES"), VAT_16);

        assertThat(result.net().amount()).isEqualByComparingTo("100.00");
        assertThat(result.tax().amount()).isEqualByComparingTo("16.00");
        assertThat(result.gross().amount()).isEqualByComparingTo("116.00");
        assertThat(result.inclusive()).isTrue();
    }

    @Test
    void addsTaxToAnExclusivePrice() {
        TaxCalculation result = TaxCalculator.addTo(Money.of("100.00", "KES"), VAT_16);

        assertThat(result.net().amount()).isEqualByComparingTo("100.00");
        assertThat(result.tax().amount()).isEqualByComparingTo("16.00");
        assertThat(result.gross().amount()).isEqualByComparingTo("116.00");
        assertThat(result.inclusive()).isFalse();
    }

    @Test
    @DisplayName("the two directions are not interchangeable")
    void inclusiveAndExclusiveDisagreeByTheRate() {
        Money amount = Money.of("100.00", "KES");

        // Treating an inclusive price as exclusive charges 16 too much on a 100 line.
        assertThat(TaxCalculator.extractFrom(amount, VAT_16).tax().amount())
                .isEqualByComparingTo("13.79");
        assertThat(TaxCalculator.addTo(amount, VAT_16).tax().amount())
                .isEqualByComparingTo("16.00");
    }

    @ParameterizedTest(name = "{0} inclusive of 16% splits into net {1} and tax {2}")
    @CsvSource({
        "116.00, 100.00, 16.00",
        "100.00,  86.21, 13.79",
        "  1.00,   0.86,  0.14",
        " 99.99,  86.20, 13.79",
        "  0.01,   0.01,  0.00",
        "250.00, 215.52, 34.48"
    })
    void inclusiveSplitsReconcile(String gross, String expectedNet, String expectedTax) {
        TaxCalculation result = TaxCalculator.extractFrom(Money.of(gross, "KES"), VAT_16);

        assertThat(result.net().amount()).isEqualByComparingTo(expectedNet);
        assertThat(result.tax().amount()).isEqualByComparingTo(expectedTax);
        // The invariant that matters: the parts always add back to the whole, at every amount.
        assertThat(result.net().add(result.tax()).amount()).isEqualByComparingTo(gross);
    }

    @Test
    @DisplayName("a zero rate produces no tax, and the amount is untouched either way")
    void zeroRatedGoodsAttractNoTax() {
        TaxCalculation inclusive = TaxCalculator.extractFrom(Money.of("60.00", "KES"), ZERO);
        assertThat(inclusive.tax().isZero()).isTrue();
        assertThat(inclusive.net().amount()).isEqualByComparingTo("60.00");

        TaxCalculation exclusive = TaxCalculator.addTo(Money.of("60.00", "KES"), ZERO);
        assertThat(exclusive.tax().isZero()).isTrue();
        assertThat(exclusive.gross().amount()).isEqualByComparingTo("60.00");
    }

    @Test
    void splitChoosesTheDirectionFromTheFlag() {
        assertThat(TaxCalculator.split(Money.of("116.00", "KES"), VAT_16, true).net().amount())
                .isEqualByComparingTo("100.00");
        assertThat(TaxCalculator.split(Money.of("100.00", "KES"), VAT_16, false).gross().amount())
                .isEqualByComparingTo("116.00");
    }

    @Test
    @DisplayName("a rate of 1 or more is rejected rather than producing nonsense")
    void impossibleRatesAreRejected() {
        // An inclusive price at a rate of 1 would imply a net amount of zero; above 1 it is
        // negative. Better to fail loudly than to price something at nothing.
        assertThatThrownBy(() -> TaxCalculator.extractFrom(Money.of("100", "KES"), BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(
                        () -> TaxCalculator.addTo(Money.of("100", "KES"), new BigDecimal("-0.1")))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> TaxCalculator.addTo(Money.of("100", "KES"), null))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    @DisplayName("repeated lines do not drift, because rounding happens once per line")
    void roundingDoesNotAccumulate() {
        Money runningGross = Money.zero(java.util.Currency.getInstance("KES"));
        Money runningTax = Money.zero(java.util.Currency.getInstance("KES"));

        for (int i = 0; i < 1000; i++) {
            TaxCalculation line = TaxCalculator.extractFrom(Money.of("9.99", "KES"), VAT_16);
            runningGross = runningGross.add(line.gross());
            runningTax = runningTax.add(line.tax());
        }

        assertThat(runningGross.amount()).isEqualByComparingTo("9990.00");
        // 1.38 per line, a thousand times. Not the 1378.97 that summing unrounded parts gives -
        // but exactly what a thousand receipts would each have shown.
        assertThat(runningTax.amount()).isEqualByComparingTo("1380.00");
    }
}
