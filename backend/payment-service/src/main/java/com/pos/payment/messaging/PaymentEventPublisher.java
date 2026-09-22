package com.pos.payment.messaging;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentAuthorizedPayload;
import com.pos.events.payments.PaymentFailedPayload;
import com.pos.events.payments.PaymentRefundedPayload;
import com.pos.messaging.outbox.OutboxRecorder;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.domain.Refund;

import lombok.RequiredArgsConstructor;

/**
 * Tells sales what became of its request - through the outbox, in the caller's transaction.
 *
 * <p>Keyed by sale id, the aggregate sales settles, so every answer about one sale arrives in the
 * order it was decided.
 */
@Component
@RequiredArgsConstructor
public class PaymentEventPublisher {

    private final OutboxRecorder outbox;

    public void authorized(PaymentIntent intent) {
        outbox.record(
                Topics.PAYMENTS_PAYMENT_AUTHORIZED,
                "Sale",
                intent.getSaleId(),
                envelope(Topics.PAYMENTS_PAYMENT_AUTHORIZED, intent)
                        .payload(
                                new PaymentAuthorizedPayload(
                                        intent.getId(),
                                        intent.getSaleId(),
                                        intent.getBranchId(),
                                        intent.getMethod(),
                                        intent.getAmountAuthorized(),
                                        intent.getCurrency(),
                                        intent.getProviderReference(),
                                        intent.getApprovalCode(),
                                        intent.getSettledAt()))
                        .build());
    }

    public void failed(PaymentIntent intent) {
        outbox.record(
                Topics.PAYMENTS_PAYMENT_FAILED,
                "Sale",
                intent.getSaleId(),
                envelope(Topics.PAYMENTS_PAYMENT_FAILED, intent)
                        .payload(
                                new PaymentFailedPayload(
                                        intent.getId(),
                                        intent.getSaleId(),
                                        intent.getBranchId(),
                                        intent.getMethod(),
                                        intent.getAmount(),
                                        intent.getCurrency(),
                                        intent.getFailureCode(),
                                        intent.getFailureMessage(),
                                        intent.getSettledAt()))
                        .build());
    }

    public void refunded(Refund refund) {
        outbox.record(
                Topics.PAYMENTS_PAYMENT_REFUNDED,
                "Sale",
                refund.getSaleId(),
                EventEnvelope.<PaymentRefundedPayload>builder()
                        .topic(Topics.PAYMENTS_PAYMENT_REFUNDED)
                        .correlationId(CorrelationId.get())
                        .branchId(refund.getBranchId())
                        .payload(
                                new PaymentRefundedPayload(
                                        refund.getId(),
                                        refund.getIntentId(),
                                        refund.getSaleId(),
                                        refund.getReturnId(),
                                        refund.getBranchId(),
                                        refund.getMethod(),
                                        refund.getAmount(),
                                        refund.getCurrency(),
                                        refund.getProviderReference(),
                                        refund.getCompletedAt() == null
                                                ? Instant.now()
                                                : refund.getCompletedAt()))
                        .build());
    }

    private static <T> EventEnvelope.Builder<T> envelope(String topic, PaymentIntent intent) {
        EventEnvelope.Builder<T> builder = EventEnvelope.<T>builder().topic(topic);
        // The request's correlation, not the thread's: this often runs from a callback or a sweep.
        String correlation =
                intent.getCorrelationId() != null ? intent.getCorrelationId() : CorrelationId.get();
        if (correlation != null) {
            builder.correlationId(correlation);
        }
        if (intent.getCausationId() != null) {
            builder.causationId(intent.getCausationId());
        }
        return builder.branchId(intent.getBranchId()).actorId(intent.getCashierId());
    }
}
