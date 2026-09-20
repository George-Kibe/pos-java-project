package com.pos.events;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pos.events.auth.OtpPurpose;
import com.pos.events.auth.OtpRequestedPayload;

class EventEnvelopeTest {

    private static OtpRequestedPayload payload() {
        return new OtpRequestedPayload(
                UUID.randomUUID(),
                "cashier@example.com",
                "Ada",
                "123456",
                OtpPurpose.REGISTRATION,
                Instant.now().plusSeconds(600));
    }

    @Test
    void builderDefaultsIdAndTimestampSoCallersNeedOnlyTypeAndPayload() {
        EventEnvelope<OtpRequestedPayload> e =
                EventEnvelope.<OtpRequestedPayload>builder()
                        .topic(Topics.AUTH_OTP_REQUESTED)
                        .payload(payload())
                        .build();

        assertThat(e.eventId()).isNotBlank();
        assertThat(e.occurredAt()).isNotNull();
        assertThat(e.eventType()).isEqualTo("auth.otp-requested");
        assertThat(e.schemaVersion()).isEqualTo(1);
    }

    @Test
    void topicSetsBothEventTypeAndSchemaVersion() {
        EventEnvelope<OtpRequestedPayload> e =
                EventEnvelope.<OtpRequestedPayload>builder()
                        .topic("pos.sales.sale-completed.v3")
                        .payload(payload())
                        .build();

        assertThat(e.eventType()).isEqualTo("sales.sale-completed");
        assertThat(e.schemaVersion()).isEqualTo(3);
    }

    @Test
    void correlationAndCausationAreCarriedThrough() {
        UUID branch = UUID.randomUUID();
        UUID actor = UUID.randomUUID();

        EventEnvelope<OtpRequestedPayload> e =
                EventEnvelope.<OtpRequestedPayload>builder()
                        .topic(Topics.AUTH_OTP_REQUESTED)
                        .correlationId("corr-1")
                        .causationId("cause-1")
                        .branchId(branch)
                        .actorId(actor)
                        .eventId("fixed-id")
                        .occurredAt(Instant.EPOCH)
                        .payload(payload())
                        .build();

        assertThat(e.correlationId()).isEqualTo("corr-1");
        assertThat(e.causationId()).isEqualTo("cause-1");
        assertThat(e.branchId()).isEqualTo(branch);
        assertThat(e.actorId()).isEqualTo(actor);
        assertThat(e.eventId()).isEqualTo("fixed-id");
        assertThat(e.occurredAt()).isEqualTo(Instant.EPOCH);
    }

    @Test
    void anEnvelopeWithoutAPayloadIsRejected() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.<OtpRequestedPayload>builder()
                                        .topic(Topics.AUTH_OTP_REQUESTED)
                                        .build())
                .isInstanceOf(NullPointerException.class)
                .hasMessageContaining("payload");
    }

    @Test
    void schemaVersionMustBeAtLeastOne() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.<OtpRequestedPayload>builder()
                                        .eventType("auth.otp-requested")
                                        .schemaVersion(0)
                                        .payload(payload())
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("schemaVersion");
    }

    @Test
    void blankIdentifiersAreRejected() {
        assertThatThrownBy(
                        () ->
                                EventEnvelope.<OtpRequestedPayload>builder()
                                        .eventId("")
                                        .eventType("auth.otp-requested")
                                        .payload(payload())
                                        .build())
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("eventId");
    }
}
