package com.pos.inventory.messaging;

import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.catalog.ProductChangedPayload;
import com.pos.events.purchasing.GoodsReceivedPayload;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.inventory.service.StockService;
import com.pos.messaging.idempotency.IdempotentConsumer;

import lombok.RequiredArgsConstructor;

/**
 * Keeps stock in step with what happens elsewhere.
 *
 * <p>Every listener is transactional and idempotent. Kafka delivers at least once, and a
 * redelivered sale here would deduct the stock twice - a difference nobody notices until a count
 * comes up short, by which time the cause is weeks in the past. The marker is written before the
 * work and rolled back with it, so a redelivery after a failure is a genuine retry rather than
 * being skipped.
 */
@Component
@RequiredArgsConstructor
public class InventoryEventListener {

    private static final Logger log = LoggerFactory.getLogger(InventoryEventListener.class);

    private static final String SALE_CONSUMER = "inventory.deduct-on-sale";
    private static final String RETURN_CONSUMER = "inventory.restock-on-return";
    private static final String RECEIPT_CONSUMER = "inventory.receive-on-goods-received";
    private static final String PRODUCT_CONSUMER = "inventory.cache-product-details";

    private final IdempotentConsumer idempotentConsumer;
    private final StockService stock;

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
                    List<StockService.SaleLine> lines =
                            sale.lines().stream()
                                    .map(
                                            line ->
                                                    new StockService.SaleLine(
                                                            line.productId(),
                                                            line.sku(),
                                                            line.quantity(),
                                                            line.batchNumber()))
                                    .toList();
                    stock.deductForSale(sale.saleId(), sale.branchId(), lines);
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
                    ReturnProcessedPayload returned = envelope.payload();
                    List<StockService.ReturnLine> lines =
                            returned.lines().stream()
                                    .map(
                                            line ->
                                                    new StockService.ReturnLine(
                                                            line.productId(),
                                                            line.sku(),
                                                            line.quantity(),
                                                            line.resaleable(),
                                                            line.batchNumber()))
                                    .toList();
                    stock.restockFromReturn(returned.returnId(), returned.branchId(), lines);
                });
    }

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
                RECEIPT_CONSUMER,
                envelope -> {
                    GoodsReceivedPayload grn = envelope.payload();
                    List<StockService.ReceiptLine> lines =
                            grn.lines().stream()
                                    .map(
                                            line ->
                                                    new StockService.ReceiptLine(
                                                            line.productId(),
                                                            line.sku(),
                                                            line.quantity(),
                                                            line.batchNumber(),
                                                            line.expiryDate(),
                                                            line.unitCost(),
                                                            line.currency()))
                                    .toList();
                    stock.receive(
                            grn.branchId(), grn.goodsReceivedNoteId(), "GoodsReceivedNote", lines);
                });
    }

    /**
     * Caches the product details a stock report needs.
     *
     * <p>Kept locally so listing stock does not call catalog once per line. Services do not share
     * tables, and a join across schemas is not available even if it were wanted.
     */
    @KafkaListener(
            topics = Topics.CATALOG_PRODUCT_CHANGED,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onProductChanged(String message) {
        EventEnvelope<ProductChangedPayload> event =
                EventJson.readEnvelope(message, ProductChangedPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                PRODUCT_CONSUMER,
                envelope -> {
                    ProductChangedPayload product = envelope.payload();
                    int refreshed =
                            stock.refreshProductDetails(
                                    product.productId(),
                                    product.sku(),
                                    product.name(),
                                    product.unitOfMeasure());
                    if (refreshed > 0) {
                        log.debug(
                                "Refreshed cached details for {} stock rows of {}",
                                refreshed,
                                product.sku());
                    }
                });
    }
}
