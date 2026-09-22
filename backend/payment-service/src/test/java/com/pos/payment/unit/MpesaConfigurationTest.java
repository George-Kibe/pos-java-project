package com.pos.payment.unit;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatNoException;
import static org.assertj.core.api.Assertions.assertThatThrownBy;
import static org.mockito.Mockito.mock;
import static org.mockito.Mockito.verifyNoInteractions;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pos.common.error.Errors;
import com.pos.payment.client.daraja.DarajaClient;
import com.pos.payment.client.daraja.DarajaProperties;
import com.pos.payment.provider.MpesaProvider;
import com.pos.payment.provider.PaymentProvider;
import com.pos.payment.service.CallbackGuard;

/** What happens when M-Pesa is not, or only partly, set up - and who may call back. */
class MpesaConfigurationTest {

    private static DarajaProperties properties(
            String key, String token, List<String> ips, Integer hops, String initiator) {
        return new DarajaProperties(
                "sandbox",
                null,
                key,
                key == null ? null : "secret",
                key == null ? null : "600000",
                key == null ? null : "passkey",
                "https://pos.example.test",
                token,
                initiator,
                initiator == null ? null : "credential",
                null,
                null,
                ips,
                hops,
                null,
                null,
                null,
                null,
                null,
                null);
    }

    @Test
    void anUnconfiguredInstallationFailsMpesaTendersWithoutCallingDaraja() {
        DarajaClient daraja = mock(DarajaClient.class);
        MpesaProvider provider =
                new MpesaProvider(daraja, properties(null, "token", List.of(), 1, null));

        PaymentProvider.Outcome outcome =
                provider.initiate(
                        new PaymentProvider.Request(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                null,
                                new BigDecimal("100"),
                                "KES",
                                "0712345678",
                                null));

        assertThat(outcome)
                .isInstanceOfSatisfying(
                        PaymentProvider.Outcome.Failed.class,
                        failed -> assertThat(failed.code()).isEqualTo("PROVIDER_UNAVAILABLE"));
        verifyNoInteractions(daraja);
    }

    @Test
    void pushingNeedsEveryCredentialAndReversingNeedsTheInitiatorToo() {
        assertThat(properties("key", "token", List.of(), 1, null).isConfigured()).isTrue();
        assertThat(properties("key", "token", List.of(), 1, null).canReverse()).isFalse();
        assertThat(properties("key", "token", List.of(), 1, "api-op").canReverse()).isTrue();
        // No callback token, no M-Pesa: an open callback would let anyone mark a sale paid.
        assertThat(properties("key", null, List.of(), 1, null).isConfigured()).isFalse();
    }

    @Test
    void theHostFollowsTheEnvironmentUnlessOverridden() {
        assertThat(properties("key", "t", List.of(), 1, null).host())
                .isEqualTo("https://sandbox.safaricom.co.ke");
    }

    @Test
    void withNoTokenConfiguredEveryCallbackIsRefused() {
        CallbackGuard guard = new CallbackGuard(properties("key", null, List.of(), 1, null));

        assertThatThrownBy(() -> guard.verify("anything", "1.2.3.4", null))
                .isInstanceOf(Errors.NotFoundException.class);
        assertThatThrownBy(() -> guard.verify(null, "1.2.3.4", null))
                .isInstanceOf(Errors.NotFoundException.class);
    }

    @Test
    void theAllowlistReadsTheAddressTheTrustedProxySaw() {
        CallbackGuard guard =
                new CallbackGuard(properties("key", "t", List.of("196.201.214.200"), 1, null));

        // The gateway appended the address it saw; whatever the caller wrote before it is ignored.
        assertThatNoException()
                .isThrownBy(() -> guard.verify("t", "10.0.0.5", "6.6.6.6, 196.201.214.200"));
        assertThatThrownBy(() -> guard.verify("t", "10.0.0.5", "196.201.214.200, 6.6.6.6"))
                .isInstanceOf(Errors.ForbiddenException.class);
        assertThatThrownBy(() -> guard.verify("t", "6.6.6.6", null))
                .isInstanceOf(Errors.ForbiddenException.class);
    }

    @Test
    void twoTrustedHopsReadOneFurtherLeft() {
        CallbackGuard guard =
                new CallbackGuard(properties("key", "t", List.of("196.201.214.200"), 2, null));

        assertThatNoException()
                .isThrownBy(() -> guard.verify("t", "10.0.0.5", "196.201.214.200, 172.18.0.9"));
    }
}
