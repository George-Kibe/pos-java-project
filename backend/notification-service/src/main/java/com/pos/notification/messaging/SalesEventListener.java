package com.pos.notification.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.sales.ReceiptEmailRequestedPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;
import com.pos.notification.config.NotificationProperties;
import com.pos.notification.domain.NotificationType;
import com.pos.notification.service.EmailNotificationService;
import com.pos.notification.service.ReceiptEmailModel;

import lombok.RequiredArgsConstructor;

/**
 * Emails receipts. Idempotent like every listener here: a redelivered request must not put a second
 * copy in the customer's inbox, while a second request from the lane is a new event and sends
 * again, as asked.
 */
@Component
@RequiredArgsConstructor
public class SalesEventListener {

    private static final String RECEIPT_CONSUMER = "notification.receipt-email";

    private final IdempotentConsumer idempotentConsumer;
    private final EmailNotificationService notifications;
    private final NotificationProperties properties;

    @KafkaListener(
            topics = Topics.SALES_RECEIPT_EMAIL_REQUESTED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onReceiptEmailRequested(String message) {
        EventEnvelope<ReceiptEmailRequestedPayload> event =
                EventJson.readEnvelope(message, ReceiptEmailRequestedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                RECEIPT_CONSUMER,
                envelope -> {
                    ReceiptEmailRequestedPayload payload = envelope.payload();
                    notifications.send(
                            envelope.eventId(),
                            envelope.eventType(),
                            NotificationType.RECEIPT,
                            payload.email(),
                            payload.recipientName(),
                            ReceiptEmailModel.from(payload, properties.getDisplayTimeZone()));
                });
    }
}
