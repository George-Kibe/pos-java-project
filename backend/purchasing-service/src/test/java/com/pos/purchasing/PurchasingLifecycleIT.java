package com.pos.purchasing;

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
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.inventory.LowStockPayload;
import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.PurchaseOrderStatus;
import com.pos.purchasing.domain.ReorderSuggestion;
import com.pos.purchasing.domain.ReturnReason;
import com.pos.purchasing.domain.SuggestionStatus;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierReturn;
import com.pos.purchasing.domain.SupplierReturnStatus;
import com.pos.purchasing.repository.ReorderSuggestionRepository;
import com.pos.purchasing.service.GoodsReceiptService;
import com.pos.purchasing.service.PurchaseOrderService;
import com.pos.purchasing.service.ReorderService;
import com.pos.purchasing.service.SupplierReturnService;
import com.pos.purchasing.service.SupplierService;

/** Returns, cost changes, cancellations and the reorder list - the paths after the happy one. */
class PurchasingLifecycleIT extends PurchasingTestBase {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID FLOUR = UUID.randomUUID();

    @Autowired private PurchaseOrderService orderService;
    @Autowired private GoodsReceiptService receiptService;
    @Autowired private SupplierReturnService returnService;
    @Autowired private SupplierService supplierService;
    @Autowired private ReorderService reorders;
    @Autowired private ReorderSuggestionRepository suggestions;

    // --- supplier returns -------------------------------------------------------

    @Test
    @DisplayName("a return goes draft, sent, credited - and is priced at landed cost")
    void theReturnLifecycle() {
        Supplier supplier = givenSupplier("SUP-RET1", "Returns Ltd");
        GoodsReceivedNote grn = receiveWithFreight(supplier);

        SupplierReturn drafted =
                returnService.draft(
                        supplier.getId(),
                        BRANCH,
                        grn.getId(),
                        ReturnReason.SHORT_DATED,
                        "Two weeks left on arrival",
                        List.of(
                                new SupplierReturnService.ReturnLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        "B-RET",
                                        money("10"),
                                        null)));

        assertThat(drafted.getReturnNumber()).startsWith("SR-" + LocalDate.now().getYear() + "-");
        assertThat(drafted.getStatus()).isEqualTo(SupplierReturnStatus.DRAFT);
        // 95.00 plus its share of the 500 freight over 100 units.
        assertThat(drafted.getLines().getFirst().getUnitCost()).isEqualByComparingTo("100.00");
        assertThat(drafted.getTotalAmount()).isEqualByComparingTo("1000.00");

        SupplierReturn sent = returnService.markSent(drafted.getId());
        assertThat(sent.getStatus()).isEqualTo(SupplierReturnStatus.SENT);
        assertThat(sent.getSentAt()).isNotNull();
        // Sent in the same transaction as the status: inventory takes the goods off the shelf.
        assertThat(
                        jdbc.sql(
                                        "SELECT payload FROM purchasing.outbox WHERE topic ="
                                                + " 'pos.purchasing.supplier-return-sent.v1' AND"
                                                + " aggregate_id = :id")
                                .param("id", sent.getId())
                                .query(String.class)
                                .list())
                .singleElement()
                .asString()
                .contains(sent.getReturnNumber());

        SupplierReturn credited = returnService.recordCredit(sent.getId(), "CN-7781");
        assertThat(credited.getStatus()).isEqualTo(SupplierReturnStatus.CREDITED);
        assertThat(credited.getCreditNoteRef()).isEqualTo("CN-7781");
        assertThat(credited.getCreditedAt()).isNotNull();
    }

    @Test
    @DisplayName("a credited return is finished and cannot move again")
    void aCreditedReturnIsTerminal() {
        Supplier supplier = givenSupplier("SUP-RET2", "Finished Ltd");
        GoodsReceivedNote grn = receiveWithFreight(supplier);
        SupplierReturn credited =
                returnService.recordCredit(
                        returnService
                                .markSent(
                                        returnService
                                                .draft(
                                                        supplier.getId(),
                                                        BRANCH,
                                                        grn.getId(),
                                                        ReturnReason.QUALITY,
                                                        null,
                                                        List.of(
                                                                new SupplierReturnService
                                                                        .ReturnLineRequest(
                                                                        FLOUR,
                                                                        "FLOUR-2KG",
                                                                        "Flour 2kg",
                                                                        "B-RET",
                                                                        money("1"),
                                                                        null)))
                                                .getId())
                                .getId(),
                        "CN-1");

        assertThatThrownBy(() -> returnService.markSent(credited.getId()))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("cannot become SENT");
        assertThatThrownBy(() -> returnService.cancel(credited.getId()))
                .isInstanceOf(Errors.ConflictException.class);
    }

    @Test
    @DisplayName("a return for goods with no known cost is refused rather than priced at zero")
    void aReturnWithNoCostIsRefused() {
        Supplier supplier = givenSupplier("SUP-RET3", "Unknown Cost Ltd");

        assertThatThrownBy(
                        () ->
                                returnService.draft(
                                        supplier.getId(),
                                        BRANCH,
                                        null,
                                        ReturnReason.WRONG_ITEM,
                                        null,
                                        List.of(
                                                new SupplierReturnService.ReturnLineRequest(
                                                        FLOUR,
                                                        "FLOUR-2KG",
                                                        "Flour 2kg",
                                                        null,
                                                        money("5"),
                                                        null))))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("No cost for FLOUR-2KG");
    }

    @Test
    void aDraftReturnCanBeAbandoned() {
        Supplier supplier = givenSupplier("SUP-RET4", "Changed Mind Ltd");
        GoodsReceivedNote grn = receiveWithFreight(supplier);

        SupplierReturn drafted =
                returnService.draft(
                        supplier.getId(),
                        BRANCH,
                        grn.getId(),
                        ReturnReason.OTHER,
                        null,
                        List.of(
                                new SupplierReturnService.ReturnLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        "B-RET",
                                        money("2"),
                                        null)));

        assertThat(returnService.cancel(drafted.getId()).getStatus())
                .isEqualTo(SupplierReturnStatus.CANCELLED);
    }

    // --- cost changes -----------------------------------------------------------

    @Test
    @DisplayName("a delivery at a new price emits a cost change with the previous figure")
    void aDeliveredPriceChangeIsAnnounced() {
        Supplier supplier = givenSupplier("SUP-COST", "Creeping Costs Ltd");
        supplierService.addProduct(
                supplier.getId(),
                FLOUR,
                "FLOUR-2KG",
                "Flour 2kg",
                money("95.00"),
                null,
                money("1"),
                null,
                true);

        long before = outboxCount(Topics.PURCHASING_SUPPLIER_COST_CHANGED);

        // The delivery charges 101.00, not the agreed 95.00.
        GoodsReceivedNote grn =
                receiptService.draft(
                        supplier.getId(),
                        BRANCH,
                        null,
                        "DN-COST",
                        null,
                        null,
                        null,
                        null,
                        false,
                        List.of(
                                new GoodsReceiptService.ReceiptLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("10"),
                                        null,
                                        null,
                                        money("101.00"),
                                        "B-COST",
                                        null)));
        receiptService.post(grn.getId());

        assertThat(outboxCount(Topics.PURCHASING_SUPPLIER_COST_CHANGED)).isEqualTo(before + 1);
        String payload = outboxPayload(Topics.PURCHASING_SUPPLIER_COST_CHANGED);
        assertThat(payload).contains("101.00");
        assertThat(payload).contains("\"sourceType\":\"GoodsReceivedNote\"");
    }

    @Test
    @DisplayName("renegotiating an agreed price announces the change too")
    void arRenegotiatedPriceIsAnnounced() {
        Supplier supplier = givenSupplier("SUP-RENEG", "Renegotiated Ltd");
        supplierService.addProduct(
                supplier.getId(),
                FLOUR,
                "FLOUR-2KG",
                "Flour 2kg",
                money("95.00"),
                null,
                null,
                null,
                false);

        long before = outboxCount(Topics.PURCHASING_SUPPLIER_COST_CHANGED);

        supplierService.addProduct(
                supplier.getId(),
                FLOUR,
                "FLOUR-2KG",
                "Flour 2kg",
                money("99.00"),
                null,
                null,
                null,
                false);

        assertThat(outboxCount(Topics.PURCHASING_SUPPLIER_COST_CHANGED)).isEqualTo(before + 1);

        // Re-saving the same price says nothing, so the topic is not a heartbeat.
        supplierService.addProduct(
                supplier.getId(),
                FLOUR,
                "FLOUR-2KG",
                "Flour 2kg",
                money("99.00"),
                null,
                null,
                null,
                false);
        assertThat(outboxCount(Topics.PURCHASING_SUPPLIER_COST_CHANGED)).isEqualTo(before + 1);
    }

    @Test
    @DisplayName("only one supplier can be preferred for a product")
    void thePreferredSupplierIsExclusive() {
        Supplier first = givenSupplier("SUP-PREF1", "First Choice Ltd");
        Supplier second = givenSupplier("SUP-PREF2", "Second Choice Ltd");

        supplierService.addProduct(
                first.getId(),
                FLOUR,
                "FLOUR-2KG",
                "Flour 2kg",
                money("95.00"),
                null,
                null,
                null,
                true);
        supplierService.addProduct(
                second.getId(),
                FLOUR,
                "FLOUR-2KG",
                "Flour 2kg",
                money("92.00"),
                null,
                null,
                null,
                true);

        // A partial unique index enforces this, so the old flag has to be cleared first.
        Long preferred =
                jdbc.sql(
                                """
                                SELECT count(*) FROM purchasing.supplier_products
                                WHERE product_id = :productId AND is_preferred
                                """)
                        .param("productId", FLOUR)
                        .query(Long.class)
                        .single();
        assertThat(preferred).isEqualTo(1);
    }

    // --- the reorder list -------------------------------------------------------

    @Test
    @DisplayName("a dismissed suggestion is not proposed again by the next low-stock event")
    void aDismissalIsHonoured() {
        UUID product = UUID.randomUUID();
        Supplier supplier = givenSupplier("SUP-DISMISS", "Preferred Ltd");
        supplierService.addProduct(
                supplier.getId(),
                product,
                "TEA-500G",
                "Tea 500g",
                money("80.00"),
                null,
                money("6"),
                null,
                true);

        publish(
                Topics.INVENTORY_LOW_STOCK,
                lowStock(product, "TEA-500G", "2.000", "5.000", "12.000"),
                product);
        eventually(Duration.ofSeconds(30), "the first suggestion", () -> suggestionCount() == 1);

        ReorderSuggestion open =
                suggestions
                        .findByProductIdAndBranchIdAndStatus(product, BRANCH, SuggestionStatus.OPEN)
                        .orElseThrow();
        reorders.dismiss(open.getId(), "Discontinuing this line");

        // A different event, so not a redelivery - stock fell further and inventory said so again.
        publish(
                Topics.INVENTORY_LOW_STOCK,
                lowStock(product, "TEA-500G", "1.000", "5.000", "12.000"),
                product);

        eventually(
                Duration.ofSeconds(20),
                "the second event to be consumed",
                () -> processedEventCount() == 2);

        // Still nothing open: a list that re-proposes what a buyer just rejected gets ignored.
        assertThat(
                        suggestions.findByProductIdAndBranchIdAndStatus(
                                product, BRANCH, SuggestionStatus.OPEN))
                .isEmpty();
        assertThat(reorders.open(BRANCH)).isEmpty();
    }

    @Test
    void aSuggestionCannotBeDismissedTwice() {
        UUID product = UUID.randomUUID();
        ReorderSuggestion suggestion =
                reorders.suggest(
                        product,
                        BRANCH,
                        "RICE-1KG",
                        "Rice 1kg",
                        money("1.000"),
                        money("5.000"),
                        money("10.000"));

        reorders.dismiss(suggestion.getId(), "Not now");

        assertThatThrownBy(() -> reorders.dismiss(suggestion.getId(), "Still not now"))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("cannot be dismissed");
    }

    @Test
    @DisplayName("suggestions acted on are marked against the order that dealt with them")
    void orderingASuggestionRecordsWhichOrder() {
        UUID product = UUID.randomUUID();
        Supplier supplier = givenSupplier("SUP-ORDERED", "Acted On Ltd");
        ReorderSuggestion suggestion =
                reorders.suggest(
                        product,
                        BRANCH,
                        "OIL-1L",
                        "Oil 1L",
                        money("0.000"),
                        money("5.000"),
                        money("12.000"));

        PurchaseOrder order =
                orderService.create(
                        supplier.getId(),
                        BRANCH,
                        LocalDate.now().plusDays(3),
                        null,
                        false,
                        // The order answers the suggestion, and says so as it is raised.
                        List.of(suggestion.getId()),
                        List.of(
                                new PurchaseOrderService.OrderLineRequest(
                                        product,
                                        "OIL-1L",
                                        "Oil 1L",
                                        money("12"),
                                        money("180.00"),
                                        null)));

        ReorderSuggestion reloaded = suggestions.findById(suggestion.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(SuggestionStatus.ORDERED);
        assertThat(reloaded.getPurchaseOrder().getId()).isEqualTo(order.getId());
        assertThat(reorders.open(BRANCH)).isEmpty();
    }

    // --- cancelling and closing -------------------------------------------------

    @Test
    @DisplayName("an undelivered order can be cancelled, with the reason kept")
    void anUndeliveredOrderCanBeCancelled() {
        Supplier supplier = givenSupplier("SUP-CANCEL", "Cancelled Ltd");
        PurchaseOrder order =
                orderService.create(
                        supplier.getId(),
                        BRANCH,
                        null,
                        null,
                        false,
                        List.of(),
                        List.of(
                                new PurchaseOrderService.OrderLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("10"),
                                        money("95.00"),
                                        null)));

        PurchaseOrder cancelled =
                orderService.cancel(order.getId(), "Supplier went out of business");

        assertThat(cancelled.getStatus()).isEqualTo(PurchaseOrderStatus.CANCELLED);
        assertThat(cancelled.getCancellationReason()).contains("out of business");
        assertThat(cancelled.getCancelledAt()).isNotNull();
        // A status change, not a delete: the row is still there to be asked about.
        assertThat(orders.findById(order.getId())).isPresent();
    }

    @Test
    void aReceivedOrderIsClosed() {
        Supplier supplier = givenSupplier("SUP-CLOSE", "Completed Ltd");
        PurchaseOrder order =
                orderService.create(
                        supplier.getId(),
                        BRANCH,
                        null,
                        null,
                        false,
                        List.of(),
                        List.of(
                                new PurchaseOrderService.OrderLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("10"),
                                        money("95.00"),
                                        null)));
        orderService.submit(order.getId());
        orderService.approve(order.getId());
        orderService.markSent(order.getId());

        GoodsReceivedNote grn =
                receiptService.draft(
                        supplier.getId(),
                        BRANCH,
                        order.getId(),
                        "DN-CLOSE",
                        null,
                        null,
                        null,
                        null,
                        false,
                        List.of(
                                new GoodsReceiptService.ReceiptLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("10"),
                                        null,
                                        null,
                                        money("95.00"),
                                        "B-CLOSE",
                                        null)));
        receiptService.post(grn.getId());

        PurchaseOrder closed = orderService.close(order.getId());
        assertThat(closed.getStatus()).isEqualTo(PurchaseOrderStatus.CLOSED);
        assertThat(closed.getClosedAt()).isNotNull();
        assertThat(closed.getStatus().isTerminal()).isTrue();
    }

    @Test
    @DisplayName("a draft order's lines can be replaced and the totals follow")
    void draftLinesCanBeReplaced() {
        Supplier supplier = givenSupplier("SUP-RELINE", "Rewritten Ltd");
        PurchaseOrder order =
                orderService.create(
                        supplier.getId(),
                        BRANCH,
                        null,
                        null,
                        false,
                        List.of(),
                        List.of(
                                new PurchaseOrderService.OrderLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("10"),
                                        money("95.00"),
                                        null)));
        assertThat(order.getGrandTotal()).isEqualByComparingTo("950.00");

        PurchaseOrder rewritten =
                orderService.replaceLines(
                        order.getId(),
                        false,
                        List.of(
                                new PurchaseOrderService.OrderLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("20"),
                                        money("95.00"),
                                        money("0.16"))));

        assertThat(rewritten.getLines()).hasSize(1);
        assertThat(rewritten.getNetTotal()).isEqualByComparingTo("1900.00");
        assertThat(rewritten.getTaxTotal()).isEqualByComparingTo("304.00");
        assertThat(rewritten.getGrandTotal()).isEqualByComparingTo("2204.00");
    }

    @Test
    void aDeliveryCannotBeReceivedAgainstAnUnsentOrder() {
        Supplier supplier = givenSupplier("SUP-EARLY", "Too Soon Ltd");
        PurchaseOrder order =
                orderService.create(
                        supplier.getId(),
                        BRANCH,
                        null,
                        null,
                        false,
                        List.of(),
                        List.of(
                                new PurchaseOrderService.OrderLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("10"),
                                        money("95.00"),
                                        null)));

        UUID supplierId = supplier.getId();
        UUID orderId = order.getId();

        assertThatThrownBy(
                        () ->
                                receiptService.draft(
                                        supplierId,
                                        BRANCH,
                                        orderId,
                                        "DN-EARLY",
                                        null,
                                        null,
                                        null,
                                        null,
                                        false,
                                        List.of(
                                                new GoodsReceiptService.ReceiptLineRequest(
                                                        FLOUR,
                                                        "FLOUR-2KG",
                                                        "Flour 2kg",
                                                        money("10"),
                                                        null,
                                                        null,
                                                        money("95.00"),
                                                        "B-EARLY",
                                                        null))))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("cannot take a delivery");
    }

    // --- helpers ----------------------------------------------------------------

    private GoodsReceivedNote receiveWithFreight(Supplier supplier) {
        GoodsReceivedNote grn =
                receiptService.draft(
                        supplier.getId(),
                        BRANCH,
                        null,
                        "DN-FREIGHT",
                        money("500.00"),
                        null,
                        null,
                        null,
                        false,
                        List.of(
                                new GoodsReceiptService.ReceiptLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("100"),
                                        null,
                                        null,
                                        money("95.00"),
                                        "B-RET",
                                        LocalDate.now().plusMonths(3))));
        return receiptService.post(grn.getId());
    }

    private static EventEnvelope<LowStockPayload> lowStock(
            UUID productId,
            String sku,
            String onHand,
            String reorderPoint,
            String suggestedQuantity) {

        return EventEnvelope.<LowStockPayload>builder()
                .topic(Topics.INVENTORY_LOW_STOCK)
                .branchId(BRANCH)
                .payload(
                        new LowStockPayload(
                                productId,
                                sku,
                                sku,
                                BRANCH,
                                new BigDecimal(onHand),
                                new BigDecimal(reorderPoint),
                                new BigDecimal(suggestedQuantity)))
                .build();
    }

    private long processedEventCount() {
        Long count =
                jdbc.sql("SELECT count(*) FROM purchasing.processed_event")
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }
}
