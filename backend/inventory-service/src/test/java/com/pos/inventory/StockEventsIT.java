package com.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.purchasing.GoodsReceivedPayload;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;

/** Stock following what happens elsewhere: deliveries in, sales out, returns back. */
class StockEventsIT extends InventoryTestBase {

    private static final UUID BRANCH = UUID.randomUUID();

    // --- receiving --------------------------------------------------------------

    @Test
    @DisplayName("a goods-received event puts a batch on the shelf with its expiry and cost")
    void goodsReceivedCreatesABatch() {
        UUID product = UUID.randomUUID();

        publish(
                Topics.PURCHASING_GOODS_RECEIVED,
                goodsReceived(
                        product,
                        "MILK-1L",
                        "24",
                        "BATCH-A",
                        LocalDate.parse("2026-06-10"),
                        "72.00"),
                product);

        eventually(
                Duration.ofSeconds(30),
                "the delivery to be received",
                () -> onHand(product, BRANCH) != null);

        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("24");

        StockItem item = items.findByProductIdAndBranchId(product, BRANCH).orElseThrow();
        List<StockBatch> sellable = batches.findSellable(item.getId());
        assertThat(sellable).hasSize(1);
        assertThat(sellable.get(0).getBatchNumber()).isEqualTo("BATCH-A");
        assertThat(sellable.get(0).getExpiryDate()).isEqualTo(LocalDate.parse("2026-06-10"));
        // Cost is per batch: the margin on a sale depends on what that delivery cost.
        assertThat(sellable.get(0).getUnitCost()).isEqualByComparingTo("72.00");
    }

    // --- the main event: FEFO deduction -----------------------------------------

    @Test
    @DisplayName("a sale deducts oldest-dated stock first, spanning two batches when it has to")
    void saleDeductsFefoAcrossTwoBatches() {
        UUID product = UUID.randomUUID();

        // Long-dated arrives first, short-dated second. FIFO would take the wrong one.
        receive(product, "MILK-1L", "3", "LONG-DATED", "2026-07-20", "70.00");
        receive(product, "MILK-1L", "10", "SHORT-DATED", "2026-05-10", "75.00");

        eventually(
                Duration.ofSeconds(30),
                "both deliveries to land",
                () ->
                        onHand(product, BRANCH) != null
                                && onHand(product, BRANCH).compareTo(new BigDecimal("13")) == 0);

        UUID saleId = UUID.randomUUID();
        publish(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(saleId, product, "MILK-1L", "12"),
                saleId);

        eventually(
                Duration.ofSeconds(30),
                "the sale to be deducted",
                () -> onHand(product, BRANCH).compareTo(new BigDecimal("1")) == 0);

        StockItem item = items.findByProductIdAndBranchId(product, BRANCH).orElseThrow();

        // The short-dated batch emptied first, then the remainder came out of the long-dated one.
        StockBatch shortDated =
                batches.findByStockItemIdAndBatchNumber(item.getId(), "SHORT-DATED").orElseThrow();
        StockBatch longDated =
                batches.findByStockItemIdAndBatchNumber(item.getId(), "LONG-DATED").orElseThrow();

        assertThat(shortDated.getQuantity()).isEqualByComparingTo("0");
        assertThat(shortDated.getStatus().name()).isEqualTo("DEPLETED");
        assertThat(longDated.getQuantity()).isEqualByComparingTo("1");

        // One movement per batch consumed, so the cost of the sale is attributable.
        long saleMovements =
                jdbc.sql(
                                """
                                SELECT count(*) FROM inventory.stock_movements
                                WHERE reference_id = :saleId AND type = 'SALE'
                                """)
                        .param("saleId", saleId)
                        .query(Long.class)
                        .single();
        assertThat(saleMovements).isEqualTo(2);

        assertThat(outboxCount(Topics.INVENTORY_STOCK_DEDUCTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("the ledger sums exactly to the cached quantity, after every kind of movement")
    void theLedgerAlwaysReconciles() {
        UUID product = UUID.randomUUID();

        receive(product, "RICE-5KG", "20", "B1", "2026-09-01", "1200.00");
        eventually(Duration.ofSeconds(30), "the delivery", () -> onHand(product, BRANCH) != null);

        UUID saleId = UUID.randomUUID();
        publish(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(saleId, product, "RICE-5KG", "7"),
                saleId);
        eventually(
                Duration.ofSeconds(30),
                "the sale",
                () -> onHand(product, BRANCH).compareTo(new BigDecimal("13")) == 0);

        UUID returnId = UUID.randomUUID();
        publish(
                Topics.SALES_RETURN_PROCESSED,
                returnProcessed(returnId, product, "RICE-5KG", "2", true),
                returnId);
        eventually(
                Duration.ofSeconds(30),
                "the return",
                () -> onHand(product, BRANCH).compareTo(new BigDecimal("15")) == 0);

        // The cached figure is derived from the ledger and must equal it exactly. If it does not,
        // some code path changed stock without writing a movement.
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo(ledgerTotal(product, BRANCH));
        assertThat(items.findLedgerDiscrepancies()).isEmpty();
    }

    // --- idempotency ------------------------------------------------------------

    @Test
    @DisplayName("a redelivered sale deducts nothing a second time")
    void redeliveredSaleChangesNothing() {
        UUID product = UUID.randomUUID();
        receive(product, "SUGAR-2KG", "10", "B1", "2027-01-01", "250.00");
        eventually(Duration.ofSeconds(30), "the delivery", () -> onHand(product, BRANCH) != null);

        UUID saleId = UUID.randomUUID();
        EventEnvelope<SaleCompletedPayload> sale = saleCompleted(saleId, product, "SUGAR-2KG", "4");

        publish(Topics.SALES_SALE_COMPLETED, sale, saleId);
        eventually(
                Duration.ofSeconds(30),
                "the first delivery of the event",
                () -> onHand(product, BRANCH).compareTo(new BigDecimal("6")) == 0);

        long movementsAfterFirst = movementCount(product, BRANCH);

        // The same event again: a rebalance, a retry, a redeployment part-way through a batch.
        publish(Topics.SALES_SALE_COMPLETED, sale, saleId);

        eventually(
                Duration.ofSeconds(20),
                "the duplicate to be seen and skipped",
                () -> {
                    Long processed =
                            jdbc.sql(
                                            """
                                            SELECT count(*) FROM inventory.processed_event
                                            WHERE event_id = :id
                                            """)
                                    .param("id", sale.eventId())
                                    .query(Long.class)
                                    .single();
                    return processed != null && processed >= 1;
                });

        // Deducting twice is the failure nobody notices until a count comes up short weeks later.
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("6");
        assertThat(movementCount(product, BRANCH)).isEqualTo(movementsAfterFirst);
    }

    // --- shortfalls and returns -------------------------------------------------

    @Test
    @DisplayName("a sale of stock that is not there is still recorded, and flagged")
    void sellingMoreThanIsHeldGoesNegativeAndAlerts() {
        UUID product = UUID.randomUUID();
        receive(product, "BREAD", "2", "B1", "2026-05-05", "45.00");
        eventually(Duration.ofSeconds(30), "the delivery", () -> onHand(product, BRANCH) != null);

        UUID saleId = UUID.randomUUID();
        publish(Topics.SALES_SALE_COMPLETED, saleCompleted(saleId, product, "BREAD", "5"), saleId);

        eventually(
                Duration.ofSeconds(30),
                "the sale to be recorded in full",
                () -> onHand(product, BRANCH).compareTo(new BigDecimal("-3")) == 0);

        // The customer left with five loaves; recording two would put the books further from
        // reality, not closer.
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("-3");
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo(ledgerTotal(product, BRANCH));
        // And somebody is told, because every later figure for this product is now suspect.
        assertThat(outboxCount(Topics.INVENTORY_NEGATIVE_STOCK_DETECTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a resaleable return goes back on the shelf; a damaged one is written off")
    void returnsRespectTheResaleableFlag() {
        UUID resaleableProduct = UUID.randomUUID();
        UUID damagedProduct = UUID.randomUUID();

        receive(resaleableProduct, "TIN-BEANS", "10", "B1", "2027-01-01", "80.00");
        receive(damagedProduct, "GLASS-JAR", "10", "B1", "2027-01-01", "150.00");
        eventually(
                Duration.ofSeconds(30),
                "both deliveries",
                () ->
                        onHand(resaleableProduct, BRANCH) != null
                                && onHand(damagedProduct, BRANCH) != null);

        UUID goodReturn = UUID.randomUUID();
        publish(
                Topics.SALES_RETURN_PROCESSED,
                returnProcessed(goodReturn, resaleableProduct, "TIN-BEANS", "2", true),
                goodReturn);

        UUID damagedReturn = UUID.randomUUID();
        publish(
                Topics.SALES_RETURN_PROCESSED,
                returnProcessed(damagedReturn, damagedProduct, "GLASS-JAR", "2", false),
                damagedReturn);

        eventually(
                Duration.ofSeconds(30),
                "both returns",
                () ->
                        onHand(resaleableProduct, BRANCH).compareTo(new BigDecimal("12")) == 0
                                && movementCount(damagedProduct, BRANCH) >= 3);

        // Back on the shelf and sellable.
        assertThat(onHand(resaleableProduct, BRANCH)).isEqualByComparingTo("12");

        // Came back, then destroyed: the quantity is unchanged but both halves are on record, so
        // the return shows in the shrinkage report rather than vanishing as a no-op.
        assertThat(onHand(damagedProduct, BRANCH)).isEqualByComparingTo("10");
        Long writeOffs =
                jdbc.sql(
                                """
                                SELECT count(*) FROM inventory.stock_movements m
                                JOIN inventory.stock_items i ON i.id = m.stock_item_id
                                WHERE i.product_id = :productId AND m.type = 'WRITE_OFF'
                                """)
                        .param("productId", damagedProduct)
                        .query(Long.class)
                        .single();
        assertThat(writeOffs).isEqualTo(1);
    }

    @Test
    @DisplayName("falling to the reorder point raises a low-stock event")
    void lowStockIsAnnounced() {
        UUID product = UUID.randomUUID();
        receive(product, "SALT-1KG", "10", "B1", "2028-01-01", "40.00");
        eventually(Duration.ofSeconds(30), "the delivery", () -> onHand(product, BRANCH) != null);

        StockItem item = items.findByProductIdAndBranchId(product, BRANCH).orElseThrow();
        item.setReorderPoint(new BigDecimal("5"));
        item.setReorderQuantity(new BigDecimal("24"));
        items.save(item);

        UUID saleId = UUID.randomUUID();
        publish(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(saleId, product, "SALT-1KG", "6"),
                saleId);

        eventually(
                Duration.ofSeconds(30),
                "the low-stock warning",
                () -> outboxCount(Topics.INVENTORY_LOW_STOCK) == 1);

        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("4");
    }

    // --- builders ---------------------------------------------------------------

    private void receive(
            UUID product, String sku, String quantity, String batch, String expiry, String cost) {
        publish(
                Topics.PURCHASING_GOODS_RECEIVED,
                goodsReceived(product, sku, quantity, batch, LocalDate.parse(expiry), cost),
                UUID.randomUUID());
    }

    private static EventEnvelope<GoodsReceivedPayload> goodsReceived(
            UUID product,
            String sku,
            String quantity,
            String batch,
            LocalDate expiry,
            String cost) {
        return EventEnvelope.<GoodsReceivedPayload>builder()
                .topic(Topics.PURCHASING_GOODS_RECEIVED)
                .correlationId("grn-correlation")
                .branchId(BRANCH)
                .payload(
                        new GoodsReceivedPayload(
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                BRANCH,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                Instant.now(),
                                List.of(
                                        new GoodsReceivedPayload.ReceivedLine(
                                                product,
                                                sku,
                                                new BigDecimal(quantity),
                                                batch,
                                                expiry,
                                                new BigDecimal(cost),
                                                "KES"))))
                .build();
    }

    private static EventEnvelope<SaleCompletedPayload> saleCompleted(
            UUID saleId, UUID product, String sku, String quantity) {
        return EventEnvelope.<SaleCompletedPayload>builder()
                .topic(Topics.SALES_SALE_COMPLETED)
                .correlationId("sale-correlation")
                .branchId(BRANCH)
                .payload(
                        new SaleCompletedPayload(
                                saleId,
                                "R-0001",
                                BRANCH,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                null,
                                Instant.now(),
                                List.of(
                                        new SaleCompletedPayload.SaleLine(
                                                product,
                                                sku,
                                                sku,
                                                new BigDecimal(quantity),
                                                new BigDecimal("100.00"),
                                                new BigDecimal("100.00"),
                                                BigDecimal.ZERO,
                                                "ZERO_RATED",
                                                null)),
                                new BigDecimal("100.00"),
                                BigDecimal.ZERO,
                                new BigDecimal("100.00"),
                                "KES"))
                .build();
    }

    private static EventEnvelope<ReturnProcessedPayload> returnProcessed(
            UUID returnId, UUID product, String sku, String quantity, boolean resaleable) {
        return EventEnvelope.<ReturnProcessedPayload>builder()
                .topic(Topics.SALES_RETURN_PROCESSED)
                .correlationId("return-correlation")
                .branchId(BRANCH)
                .payload(
                        new ReturnProcessedPayload(
                                returnId,
                                UUID.randomUUID(),
                                BRANCH,
                                UUID.randomUUID(),
                                Instant.now(),
                                List.of(
                                        new ReturnProcessedPayload.ReturnLine(
                                                product,
                                                sku,
                                                new BigDecimal(quantity),
                                                resaleable,
                                                null,
                                                resaleable ? "CHANGED_MIND" : "DAMAGED")),
                                new BigDecimal("100.00"),
                                "KES"))
                .build();
    }
}
