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
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.test.util.ReflectionTestUtils;

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.inventory.StockValuedPayload;
import com.pos.events.purchasing.GoodsReceivedPayload;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCancelledPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.service.ReservationService;
import com.pos.inventory.service.StockValuationService;

/** Stock following what happens elsewhere: deliveries in, sales out, returns back. */
class StockEventsIT extends InventoryTestBase {

    @Autowired private ReservationService reservations;
    @Autowired private StockValuationService valuations;

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

    @Test
    @DisplayName("a completed sale consumes the holds its basket placed")
    void aCompletedSaleConsumesItsCartsHolds() {
        UUID product = UUID.randomUUID();
        receive(product, "RICE-1KG", "10", "B1", "2027-01-01", "150.00");
        eventually(Duration.ofSeconds(30), "the delivery", () -> onHand(product, BRANCH) != null);

        UUID cartId = UUID.randomUUID();
        reservations.reserve(product, BRANCH, new BigDecimal("3"), "Cart", cartId);
        assertThat(reserved(product)).isEqualByComparingTo("3");

        UUID saleId = UUID.randomUUID();
        publish(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(saleId, product, "RICE-1KG", "3", cartId),
                saleId);

        eventually(
                Duration.ofSeconds(30),
                "the sale to be deducted",
                () -> onHand(product, BRANCH).compareTo(new BigDecimal("7")) == 0);
        // Left held, the three already sold would keep reducing availability until the hold
        // expired - on a best-seller, permanently, since a new basket holds more every minute.
        assertThat(reserved(product)).isEqualByComparingTo("0");
    }

    @Test
    @DisplayName("a cancelled sale's basket holds go back to sale at once")
    void aCancelledSaleReleasesItsCartsHolds() {
        UUID product = UUID.randomUUID();
        receive(product, "OIL-1L", "10", "B1", "2027-01-01", "300.00");
        eventually(Duration.ofSeconds(30), "the delivery", () -> onHand(product, BRANCH) != null);

        UUID cartId = UUID.randomUUID();
        reservations.reserve(product, BRANCH, new BigDecimal("4"), "Cart", cartId);
        assertThat(reserved(product)).isEqualByComparingTo("4");

        UUID saleId = UUID.randomUUID();
        EventEnvelope<SaleCancelledPayload> cancelled =
                EventEnvelope.<SaleCancelledPayload>builder()
                        .topic(Topics.SALES_SALE_CANCELLED)
                        .branchId(BRANCH)
                        .payload(
                                new SaleCancelledPayload(
                                        saleId,
                                        BRANCH,
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        cartId,
                                        "Payment failed: CANCELLED_BY_USER",
                                        Instant.now()))
                        .build();
        publish(Topics.SALES_SALE_CANCELLED, cancelled, saleId);

        eventually(
                Duration.ofSeconds(30),
                "the holds to be released",
                () -> reserved(product).signum() == 0);
        // Nothing was sold: released, not deducted.
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("10");
    }

    @Test
    @DisplayName("a branch's stock is valued batch by batch at the cost it landed at")
    void aValuationPricesEveryBatchAtItsLandedCost() {
        UUID sugar = UUID.randomUUID();
        UUID flour = UUID.randomUUID();
        receive(sugar, "VAL-SUGAR", "10", "S1", "2027-01-01", "95.00");
        receive(sugar, "VAL-SUGAR", "5", "S2", "2027-02-01", "100.00");
        receive(flour, "VAL-FLOUR", "3", "F1", "2027-01-01", "50.00");
        eventually(
                Duration.ofSeconds(30),
                "the deliveries",
                () ->
                        onHand(sugar, BRANCH) != null
                                && onHand(sugar, BRANCH).compareTo(new BigDecimal("15")) == 0
                                && onHand(flour, BRANCH) != null);
        UUID saleId = UUID.randomUUID();
        publish(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(saleId, sugar, "VAL-SUGAR", "12"),
                saleId);
        eventually(
                Duration.ofSeconds(30),
                "the sale",
                () -> onHand(sugar, BRANCH).compareTo(new BigDecimal("3")) == 0);

        UUID snapshot = valuations.valueBranch(BRANCH);

        List<StockValuedPayload> pages = valuationPages(snapshot);
        assertThat(pages)
                .singleElement()
                .satisfies(
                        page -> {
                            assertThat(page.pageCount()).isEqualTo(1);
                            // FEFO took the older batch first: 3 left, all from the 100.00 batch.
                            assertThat(line(page, sugar).quantityOnHand())
                                    .isEqualByComparingTo("3");
                            assertThat(line(page, sugar).valueAtCost())
                                    .isEqualByComparingTo("300.00");
                            assertThat(line(page, flour).valueAtCost())
                                    .isEqualByComparingTo("150.00");
                        });
    }

    @Test
    @DisplayName("a large snapshot is split into pages that share one id and one instant")
    void aLargeSnapshotIsPaged() {
        for (int i = 0; i < 3; i++) {
            receive(UUID.randomUUID(), "PAGE-" + i, "1", "P" + i, "2027-01-01", "10.00");
        }
        eventually(
                Duration.ofSeconds(30),
                "the deliveries",
                () ->
                        jdbc.sql(
                                                "SELECT count(*) FROM inventory.stock_items WHERE branch_id = :b AND sku LIKE 'PAGE-%'")
                                        .param("b", BRANCH)
                                        .query(Long.class)
                                        .single()
                                == 3);
        ReflectionTestUtils.setField(valuations, "pageSize", 2);
        try {
            UUID snapshot = valuations.valueBranch(BRANCH);

            List<StockValuedPayload> pages = valuationPages(snapshot);
            assertThat(pages).isNotEmpty();
            assertThat(pages)
                    .allSatisfy(page -> assertThat(page.pageCount()).isEqualTo(pages.size()));
            assertThat(pages)
                    .extracting(StockValuedPayload::valuedAt)
                    .containsOnly(pages.getFirst().valuedAt());
            assertThat(pages.stream().mapToInt(page -> page.lines().size()).sum())
                    .isGreaterThanOrEqualTo(3);
        } finally {
            ReflectionTestUtils.setField(valuations, "pageSize", 500);
        }
    }

    private List<StockValuedPayload> valuationPages(UUID snapshotId) {
        return jdbc
                .sql(
                        "SELECT payload FROM inventory.outbox WHERE topic = :topic ORDER BY created_at")
                .param("topic", Topics.INVENTORY_STOCK_VALUED)
                .query(String.class)
                .list()
                .stream()
                .map(json -> EventJson.readEnvelope(json, StockValuedPayload.class).payload())
                .filter(page -> page.snapshotId().equals(snapshotId))
                .sorted(java.util.Comparator.comparingInt(StockValuedPayload::page))
                .toList();
    }

    private static StockValuedPayload.ValuedLine line(StockValuedPayload page, UUID product) {
        return page.lines().stream()
                .filter(line -> line.productId().equals(product))
                .findFirst()
                .orElseThrow();
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

    private BigDecimal reserved(UUID product) {
        return jdbc.sql(
                        """
                        SELECT quantity_reserved FROM inventory.stock_items
                        WHERE product_id = :product AND branch_id = :branch
                        """)
                .param("product", product)
                .param("branch", BRANCH)
                .query(BigDecimal.class)
                .single();
    }

    private static EventEnvelope<SaleCompletedPayload> saleCompleted(
            UUID saleId, UUID product, String sku, String quantity) {
        return saleCompleted(saleId, product, sku, quantity, null);
    }

    private static EventEnvelope<SaleCompletedPayload> saleCompleted(
            UUID saleId, UUID product, String sku, String quantity, UUID cartId) {
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
                                "KES",
                                cartId,
                                List.of()))
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
                                "KES",
                                null,
                                null))
                .build();
    }
}
