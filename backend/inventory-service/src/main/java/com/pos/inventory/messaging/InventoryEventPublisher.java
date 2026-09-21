package com.pos.inventory.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.inventory.BatchExpiringPayload;
import com.pos.events.inventory.LowStockPayload;
import com.pos.events.inventory.NegativeStockDetectedPayload;
import com.pos.events.inventory.StockDeductedPayload;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;
import com.pos.messaging.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;

/**
 * Announces what happened to stock.
 *
 * <p>Through the outbox, in the caller's transaction, so an event is published exactly when the
 * stock change it describes is committed - and never when it is rolled back.
 */
@Component
@RequiredArgsConstructor
public class InventoryEventPublisher {

    private final OutboxRecorder outbox;

    public void stockDeducted(
            UUID saleId, UUID branchId, List<StockDeductedPayload.DeductedLine> lines) {
        outbox.record(
                Topics.INVENTORY_STOCK_DEDUCTED,
                "Sale",
                saleId,
                EventEnvelope.<StockDeductedPayload>builder()
                        .topic(Topics.INVENTORY_STOCK_DEDUCTED)
                        .correlationId(CorrelationId.get())
                        .branchId(branchId)
                        .payload(new StockDeductedPayload(saleId, branchId, Instant.now(), lines))
                        .build());
    }

    public void lowStock(StockItem item) {
        outbox.record(
                Topics.INVENTORY_LOW_STOCK,
                "StockItem",
                item.getId(),
                EventEnvelope.<LowStockPayload>builder()
                        .topic(Topics.INVENTORY_LOW_STOCK)
                        .correlationId(CorrelationId.get())
                        .branchId(item.getBranchId())
                        .payload(
                                new LowStockPayload(
                                        item.getProductId(),
                                        item.getSku(),
                                        item.getProductName(),
                                        item.getBranchId(),
                                        item.getQuantityOnHand(),
                                        item.getReorderPoint(),
                                        item.getReorderQuantity()))
                        .build());
    }

    /**
     * Stock went below zero.
     *
     * <p>Always a symptom of something else - a delivery not yet received, a sale synced from an
     * offline till, a bad count. The sale is never blocked, because the customer has already left
     * with the goods, but every later figure for this product is suspect until someone looks.
     */
    public void negativeStock(
            StockItem item, BigDecimal attempted, String triggeredBy, UUID referenceId) {
        outbox.record(
                Topics.INVENTORY_NEGATIVE_STOCK_DETECTED,
                "StockItem",
                item.getId(),
                EventEnvelope.<NegativeStockDetectedPayload>builder()
                        .topic(Topics.INVENTORY_NEGATIVE_STOCK_DETECTED)
                        .correlationId(CorrelationId.get())
                        .branchId(item.getBranchId())
                        .payload(
                                new NegativeStockDetectedPayload(
                                        item.getProductId(),
                                        item.getSku(),
                                        item.getBranchId(),
                                        item.getQuantityOnHand(),
                                        attempted,
                                        triggeredBy,
                                        referenceId))
                        .build());
    }

    public void batchExpiring(StockBatch batch, LocalDate today) {
        StockItem item = batch.getStockItem();
        long days = ChronoUnit.DAYS.between(today, batch.getExpiryDate());

        outbox.record(
                Topics.INVENTORY_BATCH_EXPIRING,
                "StockBatch",
                batch.getId(),
                EventEnvelope.<BatchExpiringPayload>builder()
                        .topic(Topics.INVENTORY_BATCH_EXPIRING)
                        .correlationId(CorrelationId.get())
                        .branchId(item.getBranchId())
                        .payload(
                                new BatchExpiringPayload(
                                        batch.getId(),
                                        batch.getBatchNumber(),
                                        item.getProductId(),
                                        item.getSku(),
                                        item.getProductName(),
                                        item.getBranchId(),
                                        batch.getExpiryDate(),
                                        days,
                                        batch.getQuantity(),
                                        batch.getQuantity().multiply(batch.getUnitCost()),
                                        batch.getCurrency()))
                        .build());
    }
}
