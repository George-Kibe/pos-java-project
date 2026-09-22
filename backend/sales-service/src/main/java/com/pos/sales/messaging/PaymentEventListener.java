package com.pos.sales.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentAuthorizedPayload;
import com.pos.events.payments.PaymentFailedPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;
import com.pos.sales.service.PaymentSettlementService;

import lombok.RequiredArgsConstructor;

/**
 * The other half of the checkout saga.
 *
 * <p>Idempotent, and it has to be: a duplicated authorisation would mark a sale paid twice, take a
 * second receipt number and publish a second {@code sale-completed} - which would deduct the stock
 * again.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentEventListener.class);

    private static final String AUTHORIZED_CONSUMER = "sales.settle-on-payment-authorized";
    private static final String FAILED_CONSUMER = "sales.compensate-on-payment-failed";

    private final IdempotentConsumer idempotentConsumer;
    private final PaymentSettlementService settlement;

    @KafkaListener(
            topics = Topics.PAYMENTS_PAYMENT_AUTHORIZED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onPaymentAuthorized(String message) {
        EventEnvelope<PaymentAuthorizedPayload> event =
                EventJson.readEnvelope(message, PaymentAuthorizedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event, AUTHORIZED_CONSUMER, envelope -> settlement.authorize(envelope.payload()));
    }

    @KafkaListener(
            topics = Topics.PAYMENTS_PAYMENT_FAILED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onPaymentFailed(String message) {
        EventEnvelope<PaymentFailedPayload> event =
                EventJson.readEnvelope(message, PaymentFailedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event, FAILED_CONSUMER, envelope -> settlement.fail(envelope.payload()));
    }
}
