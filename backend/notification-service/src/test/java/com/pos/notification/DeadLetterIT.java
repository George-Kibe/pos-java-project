package com.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.auth.OtpPurpose;
import com.pos.events.auth.OtpRequestedPayload;

/**
 * What happens when the mail server simply will not take the message.
 *
 * <p>Points the mail client at a port with nothing behind it, which is the honest way to test this:
 * mocking the sender to throw would exercise the catch block but not the retry policy, the
 * dead-letter routing, or whether the failure record survives the rollback that follows it.
 *
 * <p>Deliberately one test rather than several. Retries continue asynchronously after an assertion
 * passes, so a second test starting - and clearing the tables - while the first event is still
 * being retried produces rows that belong to neither. One test follows one message all the way to
 * its end.
 */
class DeadLetterIT extends NotificationTestBase {

    @DynamicPropertySource
    static void unreachableMailServer(DynamicPropertyRegistry registry) {
        // Nothing listens here; the connection is refused immediately.
        registry.add("spring.mail.port", () -> 1);
    }

    @Test
    @DisplayName("an undeliverable message is retried, recorded at every step, then dead-lettered")
    void undeliverableMessageIsRetriedThenDeadLettered() {
        String code = "909090";
        EventEnvelope<OtpRequestedPayload> event =
                EventEnvelope.<OtpRequestedPayload>builder()
                        .topic(Topics.AUTH_OTP_REQUESTED)
                        .correlationId("dlt-correlation")
                        .payload(
                                new OtpRequestedPayload(
                                        UUID.randomUUID(),
                                        "unreachable@example.com",
                                        "Unreachable Person",
                                        code,
                                        OtpPurpose.REGISTRATION,
                                        Instant.now().plusSeconds(600)))
                        .build();

        publish(Topics.AUTH_OTP_REQUESTED, event, UUID.randomUUID());

        // The listener's transaction rolls back on every failed send. Written inside that
        // transaction, this row would never exist and the table would only ever show successes.
        eventually(
                Duration.ofSeconds(30),
                "a failure to be recorded despite the rollback it happens inside",
                () -> statusOf(event.eventId()) != null);

        // Four attempts with backoff, then the dead-letter topic, then the permanent record.
        eventually(
                Duration.ofSeconds(90),
                "the failure to be recorded as permanent",
                () -> "PERMANENTLY_FAILED".equals(statusOf(event.eventId())));

        // One message, several attempts - not several messages.
        assertThat(logCount()).isEqualTo(1);

        Integer attempts =
                jdbc.sql(
                                """
                                SELECT attempts FROM notification.notification_log
                                WHERE event_id = :id
                                """)
                        .param("id", event.eventId())
                        .query(Integer.class)
                        .single();
        // It really did retry rather than giving up on the first refusal, and the counter survived
        // the rollback each failure caused.
        assertThat(attempts).isGreaterThan(1);

        String row =
                jdbc.sql("SELECT notification_log::text FROM notification.notification_log")
                        .query(String.class)
                        .single();
        assertThat(row).contains("unreachable@example.com").contains("PERMANENTLY_FAILED");
        // Even on the failure path the code stays out of the database.
        assertThat(row).doesNotContain(code);

        // The idempotency marker went back with the rollback, so a redelivery would be a genuine
        // retry rather than being skipped as already handled.
        Long processed =
                jdbc.sql(
                                """
                                SELECT count(*) FROM notification.processed_event
                                WHERE event_id = :id
                                """)
                        .param("id", event.eventId())
                        .query(Long.class)
                        .single();
        assertThat(processed).isZero();
    }
}
