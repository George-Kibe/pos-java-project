package com.pos.customer.messaging;

import java.math.BigDecimal;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.customer.config.LoyaltyProperties;
import com.pos.customer.service.LoyaltyService;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCancelledPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.events.sales.SaleVoidedPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;

import lombok.RequiredArgsConstructor;

/**
 * What the tills did, as it affects a member's points.
 *
 * <p>Every handler is idempotent twice over: the consumer records the event id, and the ledger
 * itself refuses a second accrual for a sale or a second claw-back for a return. Paying a member
 * twice for one basket is the failure that matters here, and it would be invisible.
 */
@Component
@RequiredArgsConstructor
public class SalesEventListener {

    private static final Logger log = LoggerFactory.getLogger(SalesEventListener.class);

    private static final String SALE_CONSUMER = "customer.accrue-on-sale-completed";
    private static final String RETURN_CONSUMER = "customer.claw-back-on-return";
    private static final String CANCELLED_CONSUMER = "customer.reverse-on-sale-cancelled";
    private static final String VOIDED_CONSUMER = "customer.reverse-on-sale-voided";

    private final IdempotentConsumer idempotentConsumer;
    private final LoyaltyService loyalty;
    private final LoyaltyProperties properties;

    @KafkaListener(
            topics = Topics.SALES_SALE_COMPLETED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onSaleCompleted(String message) {
        EventEnvelope<SaleCompletedPayload> event =
                EventJson.readEnvelope(message, SaleCompletedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                SALE_CONSUMER,
                envelope -> {
                    SaleCompletedPayload sale = envelope.payload();
                    if (sale.customerId() == null) {
                        return; // an anonymous basket earns nothing
                    }
                    if (loyalty.find(sale.customerId()).isEmpty()) {
                        // A customer this service has never heard of: loud, because the sale named
                        // someone and the points are owed to somebody.
                        log.error(
                                "Sale {} was attributed to unknown customer {}; no points accrued",
                                sale.saleId(),
                                sale.customerId());
                        return;
                    }
                    BigDecimal spend =
                            properties.earnsOnGrandTotal() ? sale.grandTotal() : sale.netTotal();
                    loyalty.accrue(
                            sale.customerId(),
                            sale.saleId(),
                            sale.branchId(),
                            spend,
                            sale.currency());
                });
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
                event,
                RETURN_CONSUMER,
                envelope -> {
                    ReturnProcessedPayload processed = envelope.payload();
                    loyalty.clawBack(
                            processed.returnId(),
                            processed.originalSaleId(),
                            processed.refundTotal(),
                            processed.currency());
                });
    }

    /** A sale that never happened: anything it spent goes back. */
    @KafkaListener(
            topics = Topics.SALES_SALE_CANCELLED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onSaleCancelled(String message) {
        EventEnvelope<SaleCancelledPayload> event =
                EventJson.readEnvelope(message, SaleCancelledPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                CANCELLED_CONSUMER,
                envelope ->
                        loyalty.reverseRedemptions(
                                envelope.payload().saleId(),
                                "Sale cancelled: " + envelope.payload().reason()));
    }

    /** A sale undone after the fact: points spent come back, points earned go away. */
    @KafkaListener(topics = Topics.SALES_SALE_VOIDED, groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onSaleVoided(String message) {
        EventEnvelope<SaleVoidedPayload> event =
                EventJson.readEnvelope(message, SaleVoidedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                VOIDED_CONSUMER,
                envelope -> {
                    SaleVoidedPayload voided = envelope.payload();
                    loyalty.reverseRedemptions(
                            voided.saleId(), "Sale voided: " + voided.reasonCode());
                    loyalty.clawBackVoidedSale(
                            voided.saleId(), "Sale voided: " + voided.reasonCode());
                });
    }
}
