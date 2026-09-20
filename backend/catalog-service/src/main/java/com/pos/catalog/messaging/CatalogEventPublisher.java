package com.pos.catalog.messaging;

import java.math.BigDecimal;

import org.springframework.stereotype.Component;

import com.pos.catalog.domain.Product;
import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.catalog.PriceChangedPayload;
import com.pos.events.catalog.ProductChangedPayload;
import com.pos.messaging.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;

/**
 * Announces catalog changes.
 *
 * <p>Through the outbox, in the caller's transaction, so a product that was saved is always
 * announced and one that was rolled back never is. Other services keep a local copy of the few
 * product fields they need - inventory the name and unit, reporting the category - rather than
 * calling here on the checkout path.
 */
@Component
@RequiredArgsConstructor
public class CatalogEventPublisher {

    private final OutboxRecorder outbox;

    public void productChanged(Product product) {
        outbox.record(
                Topics.CATALOG_PRODUCT_CHANGED,
                "Product",
                product.getId(),
                EventEnvelope.<ProductChangedPayload>builder()
                        .topic(Topics.CATALOG_PRODUCT_CHANGED)
                        .correlationId(CorrelationId.get())
                        .payload(
                                new ProductChangedPayload(
                                        product.getId(),
                                        product.getSku(),
                                        product.getName(),
                                        product.getCategory().getId(),
                                        product.getCategory().getCode(),
                                        product.getUnitOfMeasure().getCode(),
                                        product.getTaxClass().getCode(),
                                        product.isSellByWeight(),
                                        product.isActive(),
                                        product.getBasePrice(),
                                        product.getCurrency()))
                        .build());
    }

    public void priceChanged(Product product, BigDecimal previousPrice) {
        outbox.record(
                Topics.CATALOG_PRICE_CHANGED,
                "Product",
                product.getId(),
                EventEnvelope.<PriceChangedPayload>builder()
                        .topic(Topics.CATALOG_PRICE_CHANGED)
                        .correlationId(CorrelationId.get())
                        .payload(
                                new PriceChangedPayload(
                                        product.getId(),
                                        product.getSku(),
                                        null,
                                        previousPrice,
                                        product.getBasePrice(),
                                        product.getCurrency(),
                                        product.isPriceIncludesTax()))
                        .build());
    }
}
