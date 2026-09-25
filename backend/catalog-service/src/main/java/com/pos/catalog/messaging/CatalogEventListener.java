package com.pos.catalog.messaging;

import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.service.PriceReviewService;
import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.purchasing.GoodsReceivedPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;

import lombok.RequiredArgsConstructor;

/**
 * What catalog learns from other services: a delivery's landed cost, checked against the price the
 * branch charges. A redelivered delivery opens nothing twice.
 */
@Component
@RequiredArgsConstructor
public class CatalogEventListener {

    static final String DELIVERY_CONSUMER = "catalog.review-price-on-goods-received";

    private final IdempotentConsumer idempotentConsumer;
    private final PriceReviewService priceReviews;

    @KafkaListener(
            topics = Topics.PURCHASING_GOODS_RECEIVED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onGoodsReceived(String message) {
        EventEnvelope<GoodsReceivedPayload> event =
                EventJson.readEnvelope(message, GoodsReceivedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                DELIVERY_CONSUMER,
                envelope -> {
                    GoodsReceivedPayload grn = envelope.payload();
                    priceReviews.checkDelivery(
                            grn.goodsReceivedNoteId(),
                            grn.branchId(),
                            grn.receivedAt(),
                            grn.lines().stream()
                                    .map(
                                            line ->
                                                    new PriceReviewService.DeliveredLine(
                                                            line.productId(), line.unitCost()))
                                    .toList());
                });
    }
}
