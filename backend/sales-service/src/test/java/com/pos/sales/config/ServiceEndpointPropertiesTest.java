package com.pos.sales.config;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

class ServiceEndpointPropertiesTest {

    /** payment-service's {@code pos.payment.mpesa.give-up-after}, plus its sweep interval. */
    private static final Duration MPESA_GIVE_UP = Duration.ofMinutes(3).plusSeconds(15);

    @Test
    void theDefaultPaymentTimeoutOutlastsAnMpesaPushSoASaleIsNeverCancelledWhileItsPromptIsLive() {
        var properties = new ServiceEndpointProperties(null, null, null, null, null, null, 0, null);

        assertThat(properties.paymentTimeoutOrDefault()).isGreaterThan(MPESA_GIVE_UP);
    }
}
