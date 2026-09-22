package com.pos.payment.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;
import com.pos.payment.config.PaymentProperties;
import com.pos.payment.service.PaymentIntentService;
import com.pos.payment.service.RefundService;

import lombok.RequiredArgsConstructor;

/**
 * What sales asks of this service.
 *
 * <p>Both handlers only record: the provider is called later by a dispatcher, after this commit. A
 * redelivery therefore finds the work already recorded and does nothing - it can never send a
 * second prompt to a customer's phone.
 */
@Component
@RequiredArgsConstructor
public class PaymentRequestListener {

    private static final Logger log = LoggerFactory.getLogger(PaymentRequestListener.class);

    private static final String REQUEST_CONSUMER = "payment.accept-payment-requested";
    private static final String RETURN_CONSUMER = "payment.plan-refund-on-return";

    private final IdempotentConsumer idempotentConsumer;
    private final PaymentIntentService intents;
    private final RefundService refunds;
    private final PaymentProperties properties;

    @KafkaListener(
            topics = Topics.PAYMENTS_PAYMENT_REQUESTED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onPaymentRequested(String message) {
        EventEnvelope<PaymentRequestedPayload> event =
                EventJson.readEnvelope(message, PaymentRequestedPayload.class);
        CorrelationId.set(event.correlationId());

        if (properties.isDelegated(event.payload().method())) {
            // Another service owns this tender and answers the request itself.
            log.debug(
                    "Payment request {} is a {} tender, settled elsewhere",
                    event.payload().paymentIntentId(),
                    event.payload().method());
            return;
        }
        idempotentConsumer.consumeOnce(event, REQUEST_CONSUMER, intents::accept);
    }

    @KafkaListener(
            topics = Topics.SALES_RETURN_PROCESSED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onReturnProcessed(String message) {
        EventEnvelope<ReturnProcessedPayload> event =
                EventJson.readEnvelope(message, ReturnProcessedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event, RETURN_CONSUMER, envelope -> refunds.planFor(envelope.payload()));
    }
}
