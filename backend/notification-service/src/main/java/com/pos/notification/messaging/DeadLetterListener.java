package com.pos.notification.messaging;

import java.nio.charset.StandardCharsets;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.common.header.Header;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.support.KafkaHeaders;
import org.springframework.stereotype.Component;

import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.notification.domain.NotificationType;
import com.pos.notification.service.NotificationLogService;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * Records events that ran out of retries.
 *
 * <p>Without this, a dead-lettered message is invisible: the event sits on a topic nobody watches
 * while a cashier waits for a code that will never arrive, and the first anyone hears of it is a
 * phone call. Writing the failure to {@code notification_log} makes it answerable from the same
 * place every other delivery question is answered, and gives Phase 16 something concrete to alert
 * on.
 *
 * <p>Deliberately does not retry. Reaching the dead-letter topic means four attempts already
 * failed, so whatever is wrong needs a person, not another attempt.
 */
@Component
@RequiredArgsConstructor
public class DeadLetterListener {

    private static final Logger log = LoggerFactory.getLogger(DeadLetterListener.class);

    private final NotificationLogService logService;

    @KafkaListener(
            topics = {
                Topics.AUTH_OTP_REQUESTED + Topics.DLT_SUFFIX,
                Topics.AUTH_USER_REGISTERED + Topics.DLT_SUFFIX,
                Topics.AUTH_PASSWORD_RESET_REQUESTED + Topics.DLT_SUFFIX
            },
            groupId = "${spring.kafka.consumer.group-id}-dlt")
    public void onDeadLetter(ConsumerRecord<String, String> record) {
        String originalTopic = header(record, KafkaHeaders.DLT_ORIGINAL_TOPIC);
        String reason = header(record, KafkaHeaders.DLT_EXCEPTION_MESSAGE);

        String eventId = null;
        String eventType = null;
        String recipient = null;

        try {
            JsonNode envelope = EventJson.mapper().readTree(record.value());
            eventId = text(envelope, "eventId");
            eventType = text(envelope, "eventType");
            JsonNode payload = envelope.get("payload");
            if (payload != null) {
                recipient = text(payload, "email");
            }
        } catch (Exception e) {
            // A message that cannot even be parsed still has to be recorded, or it vanishes.
            log.error("Dead-lettered message on {} could not be parsed", record.topic(), e);
        }

        logService.markPermanentlyFailed(
                eventId,
                eventType,
                typeFor(originalTopic != null ? originalTopic : record.topic()),
                recipient,
                reason == null ? "Retries exhausted" : reason);
    }

    private static NotificationType typeFor(String topic) {
        if (topic.startsWith(Topics.AUTH_USER_REGISTERED)) {
            return NotificationType.WELCOME;
        }
        if (topic.startsWith(Topics.AUTH_PASSWORD_RESET_REQUESTED)) {
            return NotificationType.PASSWORD_RESET;
        }
        return NotificationType.OTP_CODE;
    }

    private static String header(ConsumerRecord<String, String> record, String name) {
        Header header = record.headers().lastHeader(name);
        return header == null ? null : new String(header.value(), StandardCharsets.UTF_8);
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
