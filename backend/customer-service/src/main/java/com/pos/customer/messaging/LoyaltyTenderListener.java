package com.pos.customer.messaging;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.customer.service.LoyaltyService;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;

import lombok.RequiredArgsConstructor;

/**
 * Paying with points.
 *
 * <p>This service answers the loyalty tender itself rather than payment-service calling in, because
 * the settling runs in a listener with no caller token to borrow - and because a tender is
 * authorised by whoever holds the value behind it. Points live here.
 *
 * <p>The answer is always one event: authorised, or failed with a reason the lane can act on. A
 * tender that got no answer would hold the customer at the till until the sale timed out.
 */
@Component
@RequiredArgsConstructor
public class LoyaltyTenderListener {

    private static final Logger log = LoggerFactory.getLogger(LoyaltyTenderListener.class);

    private static final String CONSUMER = "customer.settle-loyalty-tender";

    private final IdempotentConsumer idempotentConsumer;
    private final LoyaltyService loyalty;
    private final CustomerEventPublisher events;

    @KafkaListener(
            topics = Topics.PAYMENTS_PAYMENT_REQUESTED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onPaymentRequested(String message) {
        EventEnvelope<PaymentRequestedPayload> event =
                EventJson.readEnvelope(message, PaymentRequestedPayload.class);
        CorrelationId.set(event.correlationId());

        if (event.payload().method() != PaymentMethod.LOYALTY) {
            return; // someone else's tender
        }

        idempotentConsumer.consumeOnce(
                event,
                CONSUMER,
                envelope -> {
                    PaymentRequestedPayload request = envelope.payload();
                    if (request.customerId() == null) {
                        events.tenderFailed(
                                request,
                                "NO_CUSTOMER",
                                "Points can only pay for a sale attached to a member",
                                envelope);
                        return;
                    }
                    if (loyalty.find(request.customerId()).isEmpty()) {
                        events.tenderFailed(
                                request,
                                "NO_LOYALTY_ACCOUNT",
                                "This customer has no loyalty account",
                                envelope);
                        return;
                    }
                    switch (loyalty.redeem(
                            request.customerId(),
                            request.paymentIntentId(),
                            request.saleId(),
                            request.branchId(),
                            request.amount(),
                            request.currency())) {
                        case LoyaltyService.Redemption.Spent spent ->
                                events.tenderAuthorized(request, spent.transaction(), envelope);
                        case LoyaltyService.Redemption.NotEnoughPoints short_ -> {
                            log.info(
                                    "Customer {} cannot cover {} {} with points: {} of {}",
                                    request.customerId(),
                                    request.currency(),
                                    request.amount(),
                                    short_.available(),
                                    short_.required());
                            events.tenderFailed(
                                    request,
                                    "INSUFFICIENT_POINTS",
                                    "The balance is %d points; %d are needed"
                                            .formatted(short_.available(), short_.required()),
                                    envelope);
                        }
                    }
                });
    }
}
