package com.pos.payment.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.pos.payment.domain.policy.MpesaAmount;

class MpesaAmountTest {

    @ParameterizedTest(name = "{0} is pushed as {1}")
    @CsvSource({
        "1052.5377, 1053",
        "1052.4999, 1052",
        "1052.5000, 1053",
        "232.0000, 232",
        "0.3000, 1",
        "0.5000, 1"
    })
    void theChargeIsWholeShillingsRoundedHalfUpAndNeverZero(String amount, String charge) {
        assertThat(MpesaAmount.chargeFor(new BigDecimal(amount))).isEqualByComparingTo(charge);
    }

    @Test
    void payingExactlyTheChargeSettlesTheWholeAmount() {
        // 1052 for 1052.40 is paid in full, not forty cents short.
        assertThat(MpesaAmount.settles(new BigDecimal("1052.4000"), new BigDecimal("1052")))
                .isEqualByComparingTo("1052.4000");
        assertThat(MpesaAmount.settles(new BigDecimal("1052.5377"), new BigDecimal("1053")))
                .isEqualByComparingTo("1052.5377");
    }

    @Test
    void aShortPaymentIsReportedAsShort() {
        assertThat(MpesaAmount.settles(new BigDecimal("1052.5377"), new BigDecimal("1000")))
                .isEqualByComparingTo("1000");
    }

    @Test
    void anUnknownPaidAmountSettlesTheIntent() {
        assertThat(MpesaAmount.settles(new BigDecimal("99.9900"), null))
                .isEqualByComparingTo("99.9900");
    }

    @Test
    void aChargeNeedsAPositiveAmount() {
        assertThatThrownBy(() -> MpesaAmount.chargeFor(BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
