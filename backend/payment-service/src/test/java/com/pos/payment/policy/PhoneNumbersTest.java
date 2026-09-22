package com.pos.payment.policy;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.ValueSource;

import com.pos.payment.domain.policy.PhoneNumbers;

class PhoneNumbersTest {

    @ParameterizedTest
    @ValueSource(
            strings = {"0712345678", "+254712345678", "254712345678", "712345678", "0712 345 678"})
    void everyWayACashierTypesANumberBecomesDarajasForm(String typed) {
        assertThat(PhoneNumbers.toMsisdn(typed)).contains("254712345678");
    }

    @Test
    void theNewerOnePrefixIsAMobileNumberToo() {
        assertThat(PhoneNumbers.toMsisdn("0110123456")).contains("254110123456");
    }

    @ParameterizedTest
    @ValueSource(strings = {"", "12345", "0201234567", "+255712345678", "07123456789", "abc"})
    void anythingElseIsRefusedRatherThanPushedToAStranger(String typed) {
        assertThat(PhoneNumbers.toMsisdn(typed)).isEmpty();
    }

    @Test
    void nullIsNotANumber() {
        assertThat(PhoneNumbers.toMsisdn(null)).isEmpty();
        assertThat(PhoneNumbers.mask(null)).isNull();
    }

    @Test
    void maskingKeepsOnlyTheLastThreeDigits() {
        assertThat(PhoneNumbers.mask("254712345678")).isEqualTo("*********678");
        assertThat(PhoneNumbers.mask("12")).isEqualTo("***");
    }
}
