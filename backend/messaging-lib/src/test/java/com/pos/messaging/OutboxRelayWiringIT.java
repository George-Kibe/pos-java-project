package com.pos.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.auth.OtpPurpose;
import com.pos.events.auth.OtpRequestedPayload;
import com.pos.messaging.outbox.OutboxRecorder;

/**
 * The relay publishes on its own, without anybody calling it.
 *
 * <p>This test exists because it once did not. The scheduler's {@code @ConditionalOnBean} named a
 * bean defined by the same auto-configuration, so the condition was evaluated before that bean
 * existed and the scheduler was silently never created. Nothing failed anywhere: the application
 * started, the outbox accepted rows, and they sat at PENDING with zero attempts and no error,
 * because nothing was asking to publish them. It surfaced only when a service downstream was
 * waiting for an email that never came.
 *
 * <p>Every other test in this module calls {@code publishDue()} directly, for determinism - which
 * is why none of them noticed. This one waits.
 */
@SpringBootTest(
        classes = MessagingTestApplication.class,
        properties = {
            "pos.outbox.enabled=true",
            "pos.outbox.poll-interval=PT0.5S",
            // Its own schema. This context has a live relay that publishes anything it finds, and
            // the other test class asserts that a row stays PENDING until it asks for it - sharing
            // one outbox table would have this relay quietly publishing that test's rows.
            "spring.flyway.schemas=relay_wiring",
            "spring.flyway.default-schema=relay_wiring",
            "spring.datasource.hikari.schema=relay_wiring"
        })
class OutboxRelayWiringIT {

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        MessagingContainers.registerDataSource(registry);
        MessagingContainers.registerKafka(registry);
    }

    @Autowired private OutboxRecorder recorder;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    @Test
    @DisplayName("a recorded event is published by the scheduled relay, with nothing driving it")
    void theRelayPublishesWithoutBeingCalled() {
        UUID aggregateId = UUID.randomUUID();
        EventEnvelope<OtpRequestedPayload> envelope =
                EventEnvelope.<OtpRequestedPayload>builder()
                        .topic(Topics.AUTH_OTP_REQUESTED)
                        .correlationId("relay-wiring")
                        .payload(
                                new OtpRequestedPayload(
                                        aggregateId,
                                        "relay@example.com",
                                        "Relay Test",
                                        "135790",
                                        OtpPurpose.REGISTRATION,
                                        Instant.now().plusSeconds(600)))
                        .build();

        new TransactionTemplate(transactionManager)
                .executeWithoutResult(
                        status ->
                                recorder.record(
                                        Topics.AUTH_OTP_REQUESTED, "User", aggregateId, envelope));

        // Note what is absent: no call to publishDue(). If the scheduler is not wired, this row
        // stays PENDING forever and the test times out - which is exactly what production did.
        long deadline = System.currentTimeMillis() + 30_000;
        String status = null;
        while (System.currentTimeMillis() < deadline) {
            status =
                    jdbc.sql("SELECT status FROM outbox WHERE event_id = :id")
                            .param("id", envelope.eventId())
                            .query(String.class)
                            .single();
            if ("PUBLISHED".equals(status)) {
                break;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }

        assertThat(status)
                .as("the scheduled relay should have published this without being asked")
                .isEqualTo("PUBLISHED");
    }
}
