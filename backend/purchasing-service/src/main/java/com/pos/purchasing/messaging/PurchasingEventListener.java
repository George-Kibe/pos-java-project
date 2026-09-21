package com.pos.purchasing.messaging;

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
import com.pos.events.inventory.LowStockPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;
import com.pos.purchasing.service.ReorderService;
import com.pos.purchasing.service.SupplierCatalogCache;

import lombok.RequiredArgsConstructor;

/**
 * What purchasing needs to hear from elsewhere.
 *
 * <p>Both listeners are idempotent. A redelivered low-stock event must not turn one shortage into
 * two suggestions, and the marker is written before the work and rolled back with it, so a
 * redelivery after a genuine failure is a retry rather than being skipped as already handled.
 */
@Component
@RequiredArgsConstructor
public class PurchasingEventListener {

    private static final Logger log = LoggerFactory.getLogger(PurchasingEventListener.class);

    private static final String LOW_STOCK_CONSUMER = "purchasing.suggest-on-low-stock";
    private static final String PRODUCT_CONSUMER = "purchasing.cache-product-details";

    private final IdempotentConsumer idempotentConsumer;
    private final ReorderService reorders;
    private final SupplierCatalogCache productCache;

    /**
     * Stock has crossed its reorder point somewhere.
     *
     * <p>Purchasing cannot see inventory's tables, so this event is the only way it learns that a
     * shelf is emptying. Acting on the event rather than on a nightly scan also means the
     * suggestion exists at the moment a buyer could still do something about it.
     */
    @KafkaListener(
            topics = Topics.INVENTORY_LOW_STOCK,
            groupId = "${spring.kafka.consumer.group-id}")
    @Transactional
    public void onLowStock(String message) {
        EventEnvelope<LowStockPayload> event =
                EventJson.readEnvelope(message, LowStockPayload.class);
        CorrelationId.set(event.correlationId());

        idempotentConsumer.consumeOnce(
                event,
                LOW_STOCK_CONSUMER,
                envelope -> {
                    LowStockPayload low = envelope.payload();
                    // Null when a buyer has recently dismissed this product; the event is still
                    // marked handled, because ignoring it was the decision.
                    if (reorders.suggest(
                                    low.productId(),
                                    low.branchId(),
                                    low.sku(),
                                    low.productName(),
                                    low.quantityOnHand(),
                                    low.reorderPoint(),
                                    low.suggestedOrderQuantity())
                            != null) {
                        log.debug(
                                "Low stock on {} at branch {}: suggestion refreshed",
                                low.sku(),
                                low.branchId());
                    }
                });
    }

    /**
     * Keeps the product details on supplier price lists current.
     *
     * <p>Cached locally because a purchase order needs a name and an SKU on every line, and calling
     * catalog once per line to build one order is a per-line network hop for data that changes
     * rarely.
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
                            productCache.refreshProductDetails(
                                    product.productId(), product.sku(), product.name());
                    if (refreshed > 0) {
                        log.debug(
                                "Refreshed {} supplier price list rows for {}",
                                refreshed,
                                product.sku());
                    }
                });
    }
}
