package com.pos.notification.messaging;

import java.net.URLEncoder;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.time.Instant;
import java.util.Map;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.auth.OtpRequestedPayload;
import com.pos.events.auth.PasswordResetRequestedPayload;
import com.pos.events.auth.UserRegisteredPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;
import com.pos.notification.config.NotificationProperties;
import com.pos.notification.domain.NotificationType;
import com.pos.notification.service.EmailNotificationService;

import lombok.RequiredArgsConstructor;

/**
 * Turns auth events into email.
 *
 * <p>Each listener is transactional and idempotent. Kafka delivers at least once, and a redelivery
 * here means a second code or a second welcome landing in somebody's inbox - confusing at best, and
 * for an OTP actively unhelpful, since only one of the two codes works.
 *
 * <p>Failures are allowed to propagate. The transaction rolls back, taking the idempotency marker
 * with it, so the redelivery is a genuine retry rather than being skipped as already handled.
 */
@Component
@RequiredArgsConstructor
public class AuthEventListener {

    /** Distinct consumer names: each is allowed to handle the same event once. */
    private static final String OTP_CONSUMER = "notification.otp-email";

    private static final String WELCOME_CONSUMER = "notification.welcome-email";
    private static final String RESET_CONSUMER = "notification.password-reset-email";

    private final IdempotentConsumer idempotentConsumer;
    private final EmailNotificationService notifications;
    private final NotificationProperties properties;

    @KafkaListener(
            topics = Topics.AUTH_OTP_REQUESTED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onOtpRequested(String message) {
        EventEnvelope<OtpRequestedPayload> event =
                EventJson.readEnvelope(message, OtpRequestedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                OTP_CONSUMER,
                envelope -> {
                    OtpRequestedPayload payload = envelope.payload();
                    notifications.send(
                            envelope.eventId(),
                            envelope.eventType(),
                            NotificationType.OTP_CODE,
                            payload.email(),
                            payload.recipientName(),
                            Map.of(
                                    "otpCode", payload.otpCode(),
                                    "expiresInMinutes", minutesUntil(payload.expiresAt()),
                                    "purpose", payload.purpose().name()));
                });
    }

    @KafkaListener(
            topics = Topics.AUTH_USER_REGISTERED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onUserRegistered(String message) {
        EventEnvelope<UserRegisteredPayload> event =
                EventJson.readEnvelope(message, UserRegisteredPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                WELCOME_CONSUMER,
                envelope -> {
                    UserRegisteredPayload payload = envelope.payload();
                    notifications.send(
                            envelope.eventId(),
                            envelope.eventType(),
                            NotificationType.WELCOME,
                            payload.email(),
                            payload.fullName(),
                            Map.of("roles", payload.roles()));
                });
    }

    @KafkaListener(
            topics = Topics.AUTH_PASSWORD_RESET_REQUESTED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onPasswordResetRequested(String message) {
        EventEnvelope<PasswordResetRequestedPayload> event =
                EventJson.readEnvelope(message, PasswordResetRequestedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                RESET_CONSUMER,
                envelope -> {
                    PasswordResetRequestedPayload payload = envelope.payload();
                    notifications.send(
                            envelope.eventId(),
                            envelope.eventType(),
                            NotificationType.PASSWORD_RESET,
                            payload.email(),
                            payload.recipientName(),
                            Map.of(
                                    "resetUrl", resetUrl(payload.resetToken()),
                                    "expiresInMinutes", minutesUntil(payload.expiresAt())));
                });
    }

    private String resetUrl(String token) {
        return "%s/reset-password?token=%s"
                .formatted(
                        properties.getAppBaseUrl(),
                        URLEncoder.encode(token, StandardCharsets.UTF_8));
    }

    /** Rounded up, so "expires in 10 minutes" never reads as 9 because of a second in transit. */
    private static long minutesUntil(Instant expiresAt) {
        if (expiresAt == null) {
            return 0;
        }
        long seconds = Duration.between(Instant.now(), expiresAt).toSeconds();
        return seconds <= 0 ? 0 : (seconds + 59) / 60;
    }
}
