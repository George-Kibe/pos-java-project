package com.pos.customer.messaging;

import java.time.Instant;

import org.springframework.stereotype.Component;

import com.pos.common.correlation.CorrelationId;
import com.pos.customer.domain.LoyaltyAccount;
import com.pos.customer.domain.LoyaltyTransaction;
import com.pos.customer.domain.MembershipTier;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.customers.LoyaltyAccruedPayload;
import com.pos.events.customers.TierChangedPayload;
import com.pos.events.payments.PaymentAuthorizedPayload;
import com.pos.events.payments.PaymentFailedPayload;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.messaging.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;

/**
 * What this service announces - through the outbox, in the caller's transaction.
 *
 * <p>Including the answer to a loyalty tender. A tender is authorised by whoever holds the value
 * behind it, and points live here: no other service can spend them, so no other service can say
 * whether they were spent.
 */
@Component
@RequiredArgsConstructor
public class CustomerEventPublisher {

    private final OutboxRecorder outbox;

    public void accrued(LoyaltyAccount account, LoyaltyTransaction accrual, String tierCode) {
        outbox.record(
                Topics.CUSTOMERS_LOYALTY_ACCRUED,
                "Customer",
                account.getCustomerId(),
                EventEnvelope.<LoyaltyAccruedPayload>builder()
                        .topic(Topics.CUSTOMERS_LOYALTY_ACCRUED)
                        .correlationId(CorrelationId.get())
                        .branchId(accrual.getBranchId())
                        .payload(
                                new LoyaltyAccruedPayload(
                                        account.getCustomerId(),
                                        account.getId(),
                                        accrual.getSaleId(),
                                        accrual.getBranchId(),
                                        accrual.getPoints(),
                                        accrual.getBalanceAfter(),
                                        accrual.getAmount(),
                                        accrual.getCurrency(),
                                        tierCode,
                                        accrual.getExpiresAt(),
                                        accrual.getOccurredAt()))
                        .build());
    }

    public void tierChanged(
            LoyaltyAccount account, String previousCode, MembershipTier tier, boolean upgrade) {
        outbox.record(
                Topics.CUSTOMERS_TIER_CHANGED,
                "Customer",
                account.getCustomerId(),
                EventEnvelope.<TierChangedPayload>builder()
                        .topic(Topics.CUSTOMERS_TIER_CHANGED)
                        .correlationId(CorrelationId.get())
                        .payload(
                                new TierChangedPayload(
                                        account.getCustomerId(),
                                        account.getId(),
                                        previousCode,
                                        tier.getCode(),
                                        account.getRollingSpend(),
                                        account.getCurrency(),
                                        upgrade,
                                        Instant.now()))
                        .build());
    }

    /** The loyalty tender was paid for out of points. */
    public void tenderAuthorized(
            PaymentRequestedPayload request,
            LoyaltyTransaction redemption,
            EventEnvelope<?> cause) {
        outbox.record(
                Topics.PAYMENTS_PAYMENT_AUTHORIZED,
                "Sale",
                request.saleId(),
                envelope(Topics.PAYMENTS_PAYMENT_AUTHORIZED, request, cause)
                        .payload(
                                new PaymentAuthorizedPayload(
                                        request.paymentIntentId(),
                                        request.saleId(),
                                        request.branchId(),
                                        PaymentMethod.LOYALTY,
                                        request.amount(),
                                        request.currency(),
                                        // What a dispute is settled with: the ledger row.
                                        redemption.getId().toString(),
                                        null,
                                        redemption.getOccurredAt()))
                        .build());
    }

    public void tenderFailed(
            PaymentRequestedPayload request,
            String reasonCode,
            String message,
            EventEnvelope<?> cause) {
        outbox.record(
                Topics.PAYMENTS_PAYMENT_FAILED,
                "Sale",
                request.saleId(),
                envelope(Topics.PAYMENTS_PAYMENT_FAILED, request, cause)
                        .payload(
                                new PaymentFailedPayload(
                                        request.paymentIntentId(),
                                        request.saleId(),
                                        request.branchId(),
                                        PaymentMethod.LOYALTY,
                                        request.amount(),
                                        request.currency(),
                                        reasonCode,
                                        message,
                                        Instant.now()))
                        .build());
    }

    private static <T> EventEnvelope.Builder<T> envelope(
            String topic, PaymentRequestedPayload request, EventEnvelope<?> cause) {
        EventEnvelope.Builder<T> builder = EventEnvelope.<T>builder().topic(topic);
        String correlation =
                cause.correlationId() != null ? cause.correlationId() : CorrelationId.get();
        if (correlation != null) {
            builder.correlationId(correlation);
        }
        return builder.causationId(cause.eventId())
                .branchId(request.branchId())
                .actorId(request.cashierId());
    }
}
