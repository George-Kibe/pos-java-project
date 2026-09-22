package com.pos.payment.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.pos.payment.domain.policy.MpesaResultCodes;

class MpesaResultCodesTest {

    @ParameterizedTest(name = "{0} -> {1}")
    @CsvSource({
        "1, INSUFFICIENT_FUNDS",
        "1001, SUBSCRIBER_BUSY",
        "1019, TRANSACTION_EXPIRED",
        "1025, PROVIDER_ERROR",
        "9999, PROVIDER_ERROR",
        "1032, CANCELLED_BY_USER",
        "1037, CUSTOMER_UNREACHABLE",
        "2001, WRONG_PIN",
        "4242, DECLINED"
    })
    void darajaCodesBecomeReasonsATillCanTranslate(int code, String reason) {
        assertThat(MpesaResultCodes.reasonFor(code)).isEqualTo(reason);
    }

    @Test
    void onlyZeroIsSuccess() {
        assertThat(MpesaResultCodes.isSuccess(0)).isTrue();
        assertThat(MpesaResultCodes.isSuccess(1032)).isFalse();
    }
}
