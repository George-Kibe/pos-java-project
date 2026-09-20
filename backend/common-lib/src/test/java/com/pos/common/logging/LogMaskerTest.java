package com.pos.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;

class LogMaskerTest {

    @Test
    void redactsSensitiveJsonFields() {
        String masked =
                LogMasker.mask(
                        "{\"email\":\"ada@example.com\",\"password\":\"hunter2\",\"otpCode\":\"123456\"}");

        assertThat(masked).doesNotContain("hunter2").doesNotContain("123456");
        assertThat(masked).contains("ada@example.com"); // not a secret, keep it for support
        assertThat(masked).contains("\"password\":\"***\"");
    }

    @Test
    void redactsKeyValueForms() {
        assertThat(LogMasker.mask("login attempt password=hunter2 for ada"))
                .contains("password=***")
                .doesNotContain("hunter2");
        assertThat(LogMasker.mask("MPESA_CONSUMER_SECRET=abc123xyz")).doesNotContain("abc123xyz");
    }

    @Test
    void redactsAuthorizationHeadersAndBareJwts() {
        assertThat(LogMasker.mask("Authorization: Bearer abc.def.ghi"))
                .doesNotContain("abc.def.ghi");

        String jwt =
                "eyJhbGciOiJSUzI1NiJ9.eyJzdWIiOiIxMjM0NTY3ODkwIn0.dBjftJeZ4CVPmB92K27uhbUJU1p1r_wW1g";
        assertThat(LogMasker.mask("token was " + jwt)).doesNotContain(jwt).contains("***");
    }

    @Test
    void keepsTheLastFourDigitsOfACardNumber() {
        // Reconciliation needs the last four; the rest must never be stored or logged.
        String masked = LogMasker.mask("card 4111 1111 1111 1111 approved");
        assertThat(masked).contains("************1111").doesNotContain("4111 1111");
    }

    @Test
    void masksTheMiddleOfAPhoneNumber() {
        assertThat(LogMasker.mask("customer 0712345678 called"))
                .doesNotContain("0712345678")
                .contains("7******8");
        assertThat(LogMasker.mask("customer +254712345678")).doesNotContain("254712345678");
    }

    @Test
    void leavesOrdinaryTextAlone() {
        String ordinary = "Sale 3f2a completed at branch Westlands for KES 1,250.00";
        assertThat(LogMasker.mask(ordinary)).isEqualTo(ordinary);
    }

    @Test
    void handlesNullAndEmptyInput() {
        assertThat(LogMasker.mask(null)).isNull();
        assertThat(LogMasker.mask("")).isEmpty();
    }
}
