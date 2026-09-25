package com.pos.inventory;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.common.error.Errors;
import com.pos.events.Topics;
import com.pos.inventory.domain.AdjustmentReason;
import com.pos.inventory.domain.StockAdjustment;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.StockTake;
import com.pos.inventory.domain.StockTakeLine;
import com.pos.inventory.domain.StockTransfer;
import com.pos.inventory.service.AdjustmentService;
import com.pos.inventory.service.InventorySweepService;
import com.pos.inventory.service.ReservationService;
import com.pos.inventory.service.StockService;
import com.pos.inventory.service.StockTakeService;
import com.pos.inventory.service.TransferService;

/** Adjustments, counts, transfers and holds. */
class InventoryOperationsIT extends InventoryTestBase {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();

    @Autowired private StockService stock;
    @Autowired private AdjustmentService adjustments;
    @Autowired private StockTakeService stockTakes;
    @Autowired private TransferService transfers;
    @Autowired private ReservationService reservations;
    @Autowired private InventorySweepService sweeps;

    // --- adjustments ------------------------------------------------------------

    @Test
    @DisplayName("a drafted adjustment changes nothing until it is posted")
    void adjustmentsOnlyApplyOnPosting() {
        UUID product = stockOf("SOAP", "20", "2027-01-01");

        StockAdjustment draft =
                adjustments.draft(
                        BRANCH,
                        AdjustmentReason.DAMAGE,
                        "Crushed in the aisle",
                        List.of(
                                new AdjustmentService.AdjustmentLineRequest(
                                        product, "SOAP", new BigDecimal("-3"), "Three cartons")));

        assertThat(draft.getStatus()).isEqualTo(StockAdjustment.Status.DRAFT);
        // Nothing has happened yet: this is the whole point of drafting.
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("20");

        adjustments.post(draft.getId());

        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("17");
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo(ledgerTotal(product, BRANCH));
        assertThat(outboxCount(Topics.INVENTORY_ADJUSTMENT_POSTED)).isEqualTo(1);
    }

    @Test
    @DisplayName("damage is recorded as a write-off, not a plain adjustment")
    void damageIsAWriteOff() {
        UUID product = stockOf("JAM", "10", "2027-01-01");

        adjustments.post(
                adjustments
                        .draft(
                                BRANCH,
                                AdjustmentReason.DAMAGE,
                                null,
                                List.of(
                                        new AdjustmentService.AdjustmentLineRequest(
                                                product, "JAM", new BigDecimal("-2"), null)))
                        .getId());

        // A shrinkage report has to separate "we destroyed this" from "the count was wrong".
        String type =
                jdbc.sql(
                                """
                                SELECT m.type FROM inventory.stock_movements m
                                JOIN inventory.stock_items i ON i.id = m.stock_item_id
                                WHERE i.product_id = :productId AND m.reason_code = 'DAMAGE'
                                """)
                        .param("productId", product)
                        .query(String.class)
                        .single();
        assertThat(type).isEqualTo("WRITE_OFF");

        // Announced with what it was worth: two at the 100.00 they came in at.
        var posted =
                com.pos.events.EventJson.readEnvelope(
                                jdbc.sql(
                                                "SELECT payload FROM inventory.outbox WHERE topic = :t"
                                                        + " ORDER BY created_at DESC LIMIT 1")
                                        .param("t", Topics.INVENTORY_ADJUSTMENT_POSTED)
                                        .query(String.class)
                                        .single(),
                                com.pos.events.inventory.AdjustmentPostedPayload.class)
                        .payload();
        assertThat(posted.lines().getFirst().valueAtCost()).isEqualByComparingTo("-200.00");
    }

    @Test
    @DisplayName("a posted adjustment cannot be posted again or cancelled")
    void postedAdjustmentsAreFinal() {
        UUID product = stockOf("RICE", "10", "2027-01-01");
        StockAdjustment adjustment =
                adjustments.draft(
                        BRANCH,
                        AdjustmentReason.COUNT_CORRECTION,
                        null,
                        List.of(
                                new AdjustmentService.AdjustmentLineRequest(
                                        product, "RICE", new BigDecimal("2"), null)));
        adjustments.post(adjustment.getId());

        // The movements it wrote are already in the ledger; a correction is a new adjustment.
        assertThatThrownBy(() -> adjustments.post(adjustment.getId()))
                .isInstanceOf(Errors.ConflictException.class);
        assertThatThrownBy(() -> adjustments.cancel(adjustment.getId()))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("correcting adjustment");
    }

    @Test
    void aPositiveAdjustmentBecomesSellableStock() {
        UUID product = stockOf("FLOUR", "5", "2027-01-01");

        adjustments.post(
                adjustments
                        .draft(
                                BRANCH,
                                AdjustmentReason.COUNT_CORRECTION,
                                null,
                                List.of(
                                        new AdjustmentService.AdjustmentLineRequest(
                                                product, "FLOUR", new BigDecimal("4"), null)))
                        .getId());

        StockItem item = items.findByProductIdAndBranchId(product, BRANCH).orElseThrow();
        // Found stock is real stock: it needs a batch so it can be sold and takes its place in the
        // expiry order rather than floating unattributed.
        assertThat(batches.findSellable(item.getId())).hasSize(2);
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("9");
    }

    // --- stock takes ------------------------------------------------------------

    @Test
    @DisplayName("a count posts its variances as movements, in both directions")
    void stockTakePostsVariances() {
        UUID shortProduct = stockOf("SHORT", "20", "2027-01-01");
        UUID overProduct = stockOf("OVER", "8", "2027-01-01");
        UUID matchingProduct = stockOf("EXACT", "12", "2027-01-01");

        StockTake stockTake = stockTakes.open("ST-001", BRANCH, "Monthly count");
        assertThat(stockTake.getLines()).hasSize(3);
        // The snapshot is what the system believed when counting began.
        assertThat(stockTake.getSnapshotAt()).isNotNull();

        StockTake counted =
                stockTakes.count(
                        stockTake.getId(),
                        List.of(
                                new StockTakeService.CountLine(
                                        itemIdOf(shortProduct),
                                        new BigDecimal("17"),
                                        "Three missing"),
                                new StockTakeService.CountLine(
                                        itemIdOf(overProduct),
                                        new BigDecimal("11"),
                                        "Found in the back"),
                                new StockTakeService.CountLine(
                                        itemIdOf(matchingProduct), new BigDecimal("12"), null)));

        assertThat(counted.getLines().stream().filter(StockTakeLine::hasVariance)).hasSize(2);

        stockTakes.submitForReview(stockTake.getId());
        stockTakes.post(stockTake.getId());

        assertThat(onHand(shortProduct, BRANCH)).isEqualByComparingTo("17");
        assertThat(onHand(overProduct, BRANCH)).isEqualByComparingTo("11");
        // A line that matched writes nothing: there is no movement to record.
        assertThat(onHand(matchingProduct, BRANCH)).isEqualByComparingTo("12");
        assertThat(movementCount(matchingProduct, BRANCH)).isEqualTo(1);

        // And the ledger still reconciles after posting.
        assertThat(items.findLedgerDiscrepancies()).isEmpty();

        // The differences are announced like an adjustment, so shrinkage counts what went missing.
        var announced =
                com.pos.events.EventJson.readEnvelope(
                                jdbc.sql(
                                                "SELECT payload FROM inventory.outbox WHERE topic = :t"
                                                        + " AND aggregate_id = :id")
                                        .param("t", Topics.INVENTORY_ADJUSTMENT_POSTED)
                                        .param("id", stockTake.getId())
                                        .query(String.class)
                                        .single(),
                                com.pos.events.inventory.AdjustmentPostedPayload.class)
                        .payload();
        assertThat(announced.reasonCode()).isEqualTo("STOCK_TAKE");
        assertThat(announced.lines()).hasSize(2);
        assertThat(announced.lines())
                .anySatisfy(
                        line -> {
                            assertThat(line.productId()).isEqualTo(shortProduct);
                            assertThat(line.valueAtCost()).isEqualByComparingTo("-300.00");
                        });
    }

    @Test
    @DisplayName("an uncounted line is left alone rather than treated as zero")
    void uncountedLinesAreNotWrittenOff() {
        UUID counted = stockOf("COUNTED", "10", "2027-01-01");
        UUID skipped = stockOf("SKIPPED", "15", "2027-01-01");

        StockTake stockTake = stockTakes.open("ST-002", BRANCH, null);
        stockTakes.count(
                stockTake.getId(),
                List.of(
                        new StockTakeService.CountLine(
                                itemIdOf(counted), new BigDecimal("9"), null)));
        stockTakes.post(stockTake.getId());

        assertThat(onHand(counted, BRANCH)).isEqualByComparingTo("9");
        // A line nobody reached is not evidence the shelf is empty.
        assertThat(onHand(skipped, BRANCH)).isEqualByComparingTo("15");
    }

    @Test
    void aPostedStockTakeCannotBePostedAgain() {
        stockOf("ANY", "5", "2027-01-01");
        StockTake stockTake = stockTakes.open("ST-003", BRANCH, null);
        stockTakes.post(stockTake.getId());

        assertThatThrownBy(() -> stockTakes.post(stockTake.getId()))
                .isInstanceOf(Errors.ConflictException.class);
    }

    // --- transfers --------------------------------------------------------------

    @Test
    @DisplayName("stock belongs to neither shelf while it is in the van")
    void transfersHaveAnInTransitState() {
        UUID product = stockOf("OIL", "30", "2027-01-01");

        StockTransfer transfer =
                transfers.draft(
                        "TRF-001",
                        BRANCH,
                        OTHER_BRANCH,
                        "Weekly top-up",
                        List.of(
                                new TransferService.TransferLineRequest(
                                        product, "OIL", new BigDecimal("10"))));

        // Drafting moves nothing.
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("30");

        transfers.dispatch(transfer.getId());

        // Gone from the sender, not yet at the receiver. That gap is the truth.
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("20");
        assertThat(onHand(product, OTHER_BRANCH)).isNull();

        transfers.receive(transfer.getId(), List.of());

        assertThat(onHand(product, OTHER_BRANCH)).isEqualByComparingTo("10");
        assertThat(items.findLedgerDiscrepancies()).isEmpty();
    }

    @Test
    @DisplayName("a short receipt is recorded as what arrived, not topped up to what was sent")
    void aShortReceiptIsRecordedHonestly() {
        UUID product = stockOf("SUGAR", "30", "2027-01-01");

        StockTransfer transfer =
                transfers.draft(
                        "TRF-002",
                        BRANCH,
                        OTHER_BRANCH,
                        null,
                        List.of(
                                new TransferService.TransferLineRequest(
                                        product, "SUGAR", new BigDecimal("10"))));
        transfers.dispatch(transfer.getId());

        UUID lineId = transfer.getLines().get(0).getId();
        transfers.receive(
                transfer.getId(),
                List.of(new TransferService.ReceiptLineRequest(lineId, new BigDecimal("8"))));

        assertThat(onHand(product, BRANCH)).isEqualByComparingTo("20");
        // Two went missing between the branches, and that gap is what someone needs to see.
        assertThat(onHand(product, OTHER_BRANCH)).isEqualByComparingTo("8");
    }

    @Test
    @DisplayName("dispatching more than the branch holds is refused, unlike a sale")
    void cannotDispatchStockTheBranchDoesNotHave() {
        UUID product = stockOf("TEA", "5", "2027-01-01");

        StockTransfer transfer =
                transfers.draft(
                        "TRF-003",
                        BRANCH,
                        OTHER_BRANCH,
                        null,
                        List.of(
                                new TransferService.TransferLineRequest(
                                        product, "TEA", new BigDecimal("10"))));

        // Nothing has physically happened yet, so this is a choice rather than a record - and
        // somebody is about to load a van with stock that is not there.
        assertThatThrownBy(() -> transfers.dispatch(transfer.getId()))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("does not hold enough");
    }

    // --- reservations -----------------------------------------------------------

    @Test
    @DisplayName("a hold reduces what can be promised without moving any stock")
    void reservationsReduceAvailabilityOnly() {
        UUID product = stockOf("MILK", "10", "2026-06-01");
        UUID cartId = UUID.randomUUID();

        reservations.reserve(product, BRANCH, new BigDecimal("4"), "Cart", cartId);

        StockItem item = items.findByProductIdAndBranchId(product, BRANCH).orElseThrow();
        // The goods are still on the shelf and still count in a stock take.
        assertThat(item.getQuantityOnHand()).isEqualByComparingTo("10");
        assertThat(item.getQuantityReserved()).isEqualByComparingTo("4");
        assertThat(item.quantityAvailable()).isEqualByComparingTo("6");
        // No movement, because nothing moved.
        assertThat(movementCount(product, BRANCH)).isEqualTo(1);

        reservations.release("Cart", cartId);

        item = items.findByProductIdAndBranchId(product, BRANCH).orElseThrow();
        assertThat(item.getQuantityReserved()).isEqualByComparingTo("0");
        assertThat(item.quantityAvailable()).isEqualByComparingTo("10");
    }

    @Test
    void cannotReserveMoreThanIsAvailable() {
        UUID product = stockOf("EGGS", "6", "2026-06-01");
        reservations.reserve(product, BRANCH, new BigDecimal("4"), "Cart", UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                reservations.reserve(
                                        product,
                                        BRANCH,
                                        new BigDecimal("3"),
                                        "Cart",
                                        UUID.randomUUID()))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("Only 2 available");
    }

    @Test
    @DisplayName("an abandoned hold is returned to sale by the sweep")
    void expiredHoldsAreReturnedToSale() {
        UUID product = stockOf("YOGHURT", "10", "2026-06-01");

        reservations.reserve(
                product,
                BRANCH,
                new BigDecimal("4"),
                "Cart",
                UUID.randomUUID(),
                // Already expired: a cashier who suspended a cart and went to lunch.
                Duration.ofSeconds(-1));

        assertThat(
                        items.findByProductIdAndBranchId(product, BRANCH)
                                .orElseThrow()
                                .getQuantityReserved())
                .isEqualByComparingTo("4");

        assertThat(sweeps.releaseExpiredReservations()).isEqualTo(1);

        assertThat(
                        items.findByProductIdAndBranchId(product, BRANCH)
                                .orElseThrow()
                                .quantityAvailable())
                .isEqualByComparingTo("10");
    }

    // --- expiry -----------------------------------------------------------------

    @Test
    @DisplayName("stock nearing its date is flagged early enough to do something about it")
    void nearExpiryStockIsFlagged() {
        stockOf("SOON", "10", LocalDate.now().plusDays(5).toString());
        stockOf("LATER", "10", LocalDate.now().plusDays(90).toString());

        int flagged = sweeps.scanForExpiringStock();

        assertThat(flagged).isEqualTo(1);
        assertThat(outboxCount(Topics.INVENTORY_BATCH_EXPIRING)).isEqualTo(1);
    }

    @Test
    @DisplayName("the reconciliation check finds nothing, because the ledger is the only way in")
    void reconciliationIsClean() {
        UUID product = stockOf("CHECK", "25", "2027-01-01");
        adjustments.post(
                adjustments
                        .draft(
                                BRANCH,
                                AdjustmentReason.SAMPLE,
                                null,
                                List.of(
                                        new AdjustmentService.AdjustmentLineRequest(
                                                product, "CHECK", new BigDecimal("-5"), null)))
                        .getId());

        assertThat(sweeps.reconcileLedger()).isEmpty();
        assertThat(onHand(product, BRANCH)).isEqualByComparingTo(ledgerTotal(product, BRANCH));
    }

    // --- helpers ----------------------------------------------------------------

    /** Puts stock on the shelf directly, without going through Kafka. */
    private UUID stockOf(String sku, String quantity, String expiry) {
        UUID product = UUID.randomUUID();
        stock.receive(
                BRANCH,
                UUID.randomUUID(),
                "TestSetup",
                List.of(
                        new StockService.ReceiptLine(
                                product,
                                sku,
                                new BigDecimal(quantity),
                                sku + "-B1",
                                LocalDate.parse(expiry),
                                new BigDecimal("100.00"),
                                "KES")));
        return product;
    }

    private UUID itemIdOf(UUID productId) {
        return items.findByProductIdAndBranchId(productId, BRANCH).orElseThrow().getId();
    }
}
