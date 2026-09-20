package com.pos.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pos.events.auth.OtpPurpose;
import com.pos.events.auth.OtpRequestedPayload;
import com.pos.events.auth.UserRegisteredPayload;

class EventJsonTest {

    @Test
    void envelopeSurvivesARoundTripIncludingInstantsAndUuids() {
        UUID userId = UUID.randomUUID();
        Instant expiry = Instant.parse("2026-09-20T10:15:30Z");

        EventEnvelope<OtpRequestedPayload> original =
                EventEnvelope.<OtpRequestedPayload>builder()
                        .topic(Topics.AUTH_OTP_REQUESTED)
                        .correlationId("corr-9")
                        .payload(
                                new OtpRequestedPayload(
                                        userId,
                                        "ada@example.com",
                                        "Ada",
                                        "654321",
                                        OtpPurpose.REGISTRATION,
                                        expiry))
                        .build();

        String json = EventJson.write(original);
        EventEnvelope<OtpRequestedPayload> read =
                EventJson.readEnvelope(json, OtpRequestedPayload.class);

        assertThat(read).isEqualTo(original);
        assertThat(read.payload().userId()).isEqualTo(userId);
        assertThat(read.payload().expiresAt()).isEqualTo(expiry);
        assertThat(read.payload().purpose()).isEqualTo(OtpPurpose.REGISTRATION);
    }

    @Test
    void instantsAreWrittenAsIso8601NotEpochNumbers() {
        String json =
                EventJson.write(
                        EventEnvelope.<UserRegisteredPayload>builder()
                                .topic(Topics.AUTH_USER_REGISTERED)
                                .occurredAt(Instant.parse("2026-09-20T10:15:30Z"))
                                .payload(
                                        new UserRegisteredPayload(
                                                UUID.randomUUID(),
                                                "ada@example.com",
                                                "Ada",
                                                List.of("CASHIER"),
                                                List.of()))
                                .build());

        assertThat(json).contains("2026-09-20T10:15:30Z");
    }

    @Test
    void anUnknownFieldFromANewerProducerDoesNotBreakAnOlderConsumer() {
        String fromTheFuture =
                """
                {
                  "eventId": "e-1",
                  "eventType": "auth.user-registered",
                  "schemaVersion": 1,
                  "occurredAt": "2026-09-20T10:15:30Z",
                  "somethingAddedLater": {"nested": true},
                  "payload": {
                    "userId": "11111111-1111-1111-1111-111111111111",
                    "email": "ada@example.com",
                    "fullName": "Ada",
                    "roles": ["CASHIER"],
                    "branchIds": [],
                    "alsoNew": 42
                  }
                }
                """;

        EventEnvelope<UserRegisteredPayload> read =
                EventJson.readEnvelope(fromTheFuture, UserRegisteredPayload.class);

        assertThat(read.eventId()).isEqualTo("e-1");
        assertThat(read.payload().email()).isEqualTo("ada@example.com");
        assertThat(read.payload().roles()).containsExactly("CASHIER");
    }
}
