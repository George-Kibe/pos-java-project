package com.pos.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.common.error.Errors;
import com.pos.events.Topics;
import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.domain.GrnStatus;
import com.pos.purchasing.domain.InvoiceMatchStatus;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.PurchaseOrderStatus;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.cost.AllocationBasis;
import com.pos.purchasing.service.GoodsReceiptService;
import com.pos.purchasing.service.PurchaseOrderService;
import com.pos.purchasing.service.SupplierInvoiceService;

/**
 * Order to delivery to invoice, against a real database.
 *
 * <p>These are the cases the phase is judged on: an approved order that receives goods at the right
 * landed cost, a partial delivery that leaves the order correctly part-received, and an invoice
 * that bills more than the delivery justifies.
 */
class PurchasingFlowIT extends PurchasingTestBase {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID FLOUR = UUID.randomUUID();
    private static final UUID SUGAR = UUID.randomUUID();

    @Autowired private PurchaseOrderService orderService;
    @Autowired private GoodsReceiptService receiptService;
    @Autowired private SupplierInvoiceService invoiceService;

    // --- the purchase order lifecycle -------------------------------------------

    @Test
    @DisplayName(
            "an order goes draft, submitted, approved, sent - and its total is frozen at approval")
    void theOrderLifecycleRunsInOrder() {
        PurchaseOrder order = givenSentOrder();

        assertThat(order.getOrderNumber()).startsWith("PO-" + LocalDate.now().getYear() + "-");
        assertThat(order.getStatus()).isEqualTo(PurchaseOrderStatus.SENT);
        // 9500 of zero-rated flour, 1500 of sugar, and 240 of VAT on the sugar alone.
        assertThat(order.getNetTotal()).isEqualByComparingTo("11000.00");
        assertThat(order.getTaxTotal()).isEqualByComparingTo("240.00");
        assertThat(order.getGrandTotal()).isEqualByComparingTo("11240.00");
        // Frozen: what was authorised stays readable even if the order were somehow edited later.
        assertThat(order.getApprovedTotal()).isEqualByComparingTo("11240.00");
        assertThat(order.getApprovedAt()).isNotNull();
        assertThat(outboxCount(Topics.PURCHASING_PO_APPROVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("an approved order cannot be edited, and cannot go back to draft")
    void approvalClosesTheDoorOnEditing() {
        Supplier supplier = givenSupplier("SUP-EDIT", "Editable Supplies");
        PurchaseOrder order = draftOrder(supplier);
        orderService.submit(order.getId());
        orderService.approve(order.getId());

        assertThatThrownBy(
                        () ->
                                orderService.replaceLines(
                                        order.getId(),
                                        List.of(
                                                new PurchaseOrderService.OrderLineRequest(
                                                        FLOUR,
                                                        "FLOUR-2KG",
                                                        "Flour 2kg",
                                                        money("999"),
                                                        money("95.00"),
                                                        money("0.16")))))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("cannot be edited");

        assertThatThrownBy(() -> orderService.returnToDraft(order.getId()))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("cannot become DRAFT");
    }

    @Test
    void aSubmittedOrderCanBeSentBackToTheBuyer() {
        Supplier supplier = givenSupplier("SUP-BACK", "Returnable Supplies");
        PurchaseOrder order = draftOrder(supplier);
        orderService.submit(order.getId());

        PurchaseOrder returned = orderService.returnToDraft(order.getId());

        assertThat(returned.getStatus()).isEqualTo(PurchaseOrderStatus.DRAFT);
        // And is editable again, which is the point of sending it back.
        assertThat(returned.getStatus().isEditable()).isTrue();
    }

    @Test
    @DisplayName("a supplier on hold cannot take new orders")
    void aSupplierOnHoldIsRefused() {
        Supplier supplier = givenSupplier("SUP-HOLD", "Suspended Supplies");
        supplier.setStatus(com.pos.purchasing.domain.SupplierStatus.ON_HOLD);
        suppliers.save(supplier);

        assertThatThrownBy(() -> draftOrder(supplier))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("cannot take new orders");
    }

    // --- receiving --------------------------------------------------------------

    @Test
    @DisplayName("a full delivery lands at the landed cost and completes the order")
    void aFullDeliveryReceivesEverything() {
        PurchaseOrder order = givenSentOrder();

        GoodsReceivedNote grn =
                receiptService.draft(
                        order.getSupplier().getId(),
                        BRANCH,
                        order.getId(),
                        "DN-5511",
                        money("400.00"),
                        money("100.00"),
                        AllocationBasis.BY_VALUE,
                        null,
                        List.of(
                                receiptLine(FLOUR, "FLOUR-2KG", "100", "95.00", "B-FLOUR-1"),
                                receiptLine(SUGAR, "SUGAR-1KG", "50", "30.00", "B-SUGAR-1")));

        assertThat(grn.getStatus()).isEqualTo(GrnStatus.DRAFT);
        // The discrepancy is on the receipt itself, not only discoverable by comparing documents.
        assertThat(grn.getLines().getFirst().getQuantityOrdered()).isEqualByComparingTo("100");
        assertThat(grn.getLines().getFirst().discrepancy()).isEqualByComparingTo("0");

        GoodsReceivedNote posted = receiptService.post(grn.getId());

        assertThat(posted.getStatus()).isEqualTo(GrnStatus.POSTED);
        assertThat(posted.getGoodsTotal()).isEqualByComparingTo("11000.00");
        // Goods plus the 500 of freight and duty.
        assertThat(posted.getLandedTotal()).isEqualByComparingTo("11500.00");

        // 9500 of flour out of 11000 of goods carries 431.8182 of the charges.
        var flourLine =
                posted.getLines().stream()
                        .filter(line -> line.getProductId().equals(FLOUR))
                        .findFirst()
                        .orElseThrow();
        assertThat(flourLine.getAllocatedCharges()).isEqualByComparingTo("431.8182");
        assertThat(flourLine.getLandedUnitCost()).isEqualByComparingTo("99.3182");

        BigDecimal allocated =
                posted.getLines().stream()
                        .map(line -> line.getAllocatedCharges())
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        // Nothing lost in the split: the residual has to land somewhere.
        assertThat(allocated).isEqualByComparingTo("500.00");

        PurchaseOrder reloaded = orders.findById(order.getId()).orElseThrow();
        assertThat(reloaded.getStatus()).isEqualTo(PurchaseOrderStatus.RECEIVED);
        assertThat(reloaded.isFullyReceived()).isTrue();
        assertThat(outboxCount(Topics.PURCHASING_GOODS_RECEIVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a partial delivery leaves the order partially received with the rest outstanding")
    void aPartialDeliveryLeavesTheOrderOpen() {
        PurchaseOrder order = givenSentOrder();

        GoodsReceivedNote grn =
                receiptService.draft(
                        order.getSupplier().getId(),
                        BRANCH,
                        order.getId(),
                        "DN-PART",
                        null,
                        null,
                        null,
                        null,
                        List.of(receiptLine(FLOUR, "FLOUR-2KG", "60", "95.00", "B-FLOUR-1")));
        receiptService.post(grn.getId());

        PurchaseOrder reloaded = orders.findById(order.getId()).orElseThrow();

        assertThat(reloaded.getStatus()).isEqualTo(PurchaseOrderStatus.PARTIALLY_RECEIVED);
        assertThat(reloaded.isFullyReceived()).isFalse();

        var flour =
                reloaded.getLines().stream()
                        .filter(line -> line.getProductId().equals(FLOUR))
                        .findFirst()
                        .orElseThrow();
        assertThat(flour.getQuantityReceived()).isEqualByComparingTo("60");
        assertThat(flour.quantityOutstanding()).isEqualByComparingTo("40");

        // The second half completes it.
        GoodsReceivedNote rest =
                receiptService.draft(
                        order.getSupplier().getId(),
                        BRANCH,
                        order.getId(),
                        "DN-PART-2",
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                receiptLine(FLOUR, "FLOUR-2KG", "40", "95.00", "B-FLOUR-2"),
                                receiptLine(SUGAR, "SUGAR-1KG", "50", "30.00", "B-SUGAR-1")));
        receiptService.post(rest.getId());

        assertThat(orders.findById(order.getId()).orElseThrow().getStatus())
                .isEqualTo(PurchaseOrderStatus.RECEIVED);
    }

    @Test
    @DisplayName("rejected goods never become stock, and carry none of the freight")
    void rejectedGoodsAreExcluded() {
        Supplier supplier = givenSupplier("SUP-REJ", "Damaged Goods Ltd");

        GoodsReceivedNote grn =
                receiptService.draft(
                        supplier.getId(),
                        BRANCH,
                        null,
                        "DN-REJ",
                        money("300.00"),
                        null,
                        AllocationBasis.BY_VALUE,
                        null,
                        List.of(
                                new GoodsReceiptService.ReceiptLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("100"),
                                        money("40"),
                                        "Water damage",
                                        money("95.00"),
                                        "B-WET",
                                        LocalDate.now().plusMonths(6)),
                                receiptLine(SUGAR, "SUGAR-1KG", "50", "30.00", "B-SUGAR-1")));

        GoodsReceivedNote posted = receiptService.post(grn.getId());

        var flour =
                posted.getLines().stream()
                        .filter(line -> line.getProductId().equals(FLOUR))
                        .findFirst()
                        .orElseThrow();
        assertThat(flour.quantityAccepted()).isEqualByComparingTo("60");
        // Charges are weighted on 60 units, not the 100 that arrived.
        assertThat(posted.getGoodsTotal()).isEqualByComparingTo("7200.00");

        String payload = outboxPayload(Topics.PURCHASING_GOODS_RECEIVED);
        // Inventory is told about 60, so the 40 damaged never become sellable stock.
        assertThat(payload).contains("60.000");
        assertThat(payload).doesNotContain("\"quantity\":100");
    }

    @Test
    void aDeliveryWithEverythingRejectedCannotBePosted() {
        Supplier supplier = givenSupplier("SUP-ALLREJ", "All Bad Ltd");

        GoodsReceivedNote grn =
                receiptService.draft(
                        supplier.getId(),
                        BRANCH,
                        null,
                        "DN-ALLREJ",
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                new GoodsReceiptService.ReceiptLineRequest(
                                        FLOUR,
                                        "FLOUR-2KG",
                                        "Flour 2kg",
                                        money("10"),
                                        money("10"),
                                        "Entirely spoiled",
                                        money("95.00"),
                                        "B-BAD",
                                        null)));

        assertThatThrownBy(() -> receiptService.post(grn.getId()))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("nothing to post");
    }

    @Test
    void aReceiptCannotBePostedTwice() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote grn =
                receiptService.draft(
                        order.getSupplier().getId(),
                        BRANCH,
                        order.getId(),
                        "DN-ONCE",
                        null,
                        null,
                        null,
                        null,
                        List.of(receiptLine(FLOUR, "FLOUR-2KG", "10", "95.00", "B-1")));
        receiptService.post(grn.getId());

        assertThatThrownBy(() -> receiptService.post(grn.getId()))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("already been posted");
    }

    @Test
    @DisplayName("a delivery that arrives without an order is still recorded")
    void aDeliveryWithNoOrderIsAccepted() {
        Supplier supplier = givenSupplier("SUP-NOPO", "Unannounced Deliveries");

        GoodsReceivedNote grn =
                receiptService.draft(
                        supplier.getId(),
                        BRANCH,
                        null,
                        "DN-NOPO",
                        null,
                        null,
                        null,
                        "Turned up unannounced",
                        List.of(receiptLine(FLOUR, "FLOUR-2KG", "20", "95.00", "B-NOPO")));

        GoodsReceivedNote posted = receiptService.post(grn.getId());

        assertThat(posted.getPurchaseOrder()).isNull();
        assertThat(posted.getLines().getFirst().discrepancy()).isNull();
        assertThat(outboxCount(Topics.PURCHASING_GOODS_RECEIVED)).isEqualTo(1);
    }

    @Test
    @DisplayName("an order that has been delivered against cannot be cancelled")
    void aPartlyDeliveredOrderCannotBeCancelled() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote grn =
                receiptService.draft(
                        order.getSupplier().getId(),
                        BRANCH,
                        order.getId(),
                        "DN-CANCEL",
                        null,
                        null,
                        null,
                        null,
                        List.of(receiptLine(FLOUR, "FLOUR-2KG", "10", "95.00", "B-1")));
        receiptService.post(grn.getId());

        assertThatThrownBy(() -> orderService.cancel(order.getId(), "Changed our minds"))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("close it instead");
    }

    // --- invoice matching -------------------------------------------------------

    @Test
    @DisplayName("an over-billed invoice is flagged as an exception with the variance")
    void anOverBilledInvoiceIsFlagged() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote grn = receiveInFull(order);

        // Agreed 95.00 a unit; billed 100.00.
        SupplierInvoiceService.MatchedInvoice matched =
                invoiceService.record(
                        order.getSupplier().getId(),
                        "INV-OVER-1",
                        LocalDate.now(),
                        money("11500.00"),
                        money("0.00"),
                        order.getId(),
                        grn.getId(),
                        List.of(
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        FLOUR, "FLOUR-2KG", money("100"), money("100.00")),
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        SUGAR, "SUGAR-1KG", money("50"), money("30.00"))));

        assertThat(matched.invoice().getMatchStatus()).isEqualTo(InvoiceMatchStatus.EXCEPTION);
        assertThat(matched.invoice().getVarianceAmount()).isEqualByComparingTo("500.00");
        assertThat(matched.result().isOverBilled()).isTrue();
        assertThat(matched.invoice().getMatchNotes()).contains("PRICE_VARIANCE");
        // Due date comes from the supplier's payment terms, not from the request.
        assertThat(matched.invoice().getDueDate()).isEqualTo(LocalDate.now().plusDays(30));
    }

    @Test
    void aCleanInvoiceMatchesAndBecomesPayable() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote grn = receiveInFull(order);

        SupplierInvoiceService.MatchedInvoice matched =
                invoiceService.record(
                        order.getSupplier().getId(),
                        "INV-CLEAN-1",
                        LocalDate.now(),
                        money("11000.00"),
                        money("0.00"),
                        order.getId(),
                        grn.getId(),
                        List.of(
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        FLOUR, "FLOUR-2KG", money("100"), money("95.00")),
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        SUGAR, "SUGAR-1KG", money("50"), money("30.00"))));

        assertThat(matched.invoice().getMatchStatus()).isEqualTo(InvoiceMatchStatus.MATCHED);
        assertThat(matched.invoice().getMatchStatus().isPayable()).isTrue();

        var approved = invoiceService.approveForPayment(matched.invoice().getId());
        assertThat(approved.getMatchStatus()).isEqualTo(InvoiceMatchStatus.APPROVED_FOR_PAYMENT);
        assertThat(approved.getApprovedForPaymentAt()).isNotNull();
    }

    @Test
    @DisplayName("accepting an exception requires a reason and keeps it")
    void anExceptionCanBeAcceptedOnlyWithAReason() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote grn = receiveInFull(order);

        SupplierInvoiceService.MatchedInvoice matched =
                invoiceService.record(
                        order.getSupplier().getId(),
                        "INV-ACCEPT-1",
                        LocalDate.now(),
                        money("11500.00"),
                        money("0.00"),
                        order.getId(),
                        grn.getId(),
                        List.of(
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        FLOUR, "FLOUR-2KG", money("100"), money("100.00")),
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        SUGAR, "SUGAR-1KG", money("50"), money("30.00"))));
        UUID invoiceId = matched.invoice().getId();

        assertThatThrownBy(() -> invoiceService.acceptException(invoiceId, "  "))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("requires a reason");

        var accepted =
                invoiceService.acceptException(invoiceId, "Agreed price rise, confirmed by phone");

        assertThat(accepted.getMatchStatus()).isEqualTo(InvoiceMatchStatus.APPROVED_FOR_PAYMENT);
        // Kept, so a supplier who creeps upwards every month is visible later.
        assertThat(accepted.getOverrideReason()).contains("Agreed price rise");
    }

    @Test
    void aCleanInvoiceHasNothingToAccept() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote grn = receiveInFull(order);

        SupplierInvoiceService.MatchedInvoice matched =
                invoiceService.record(
                        order.getSupplier().getId(),
                        "INV-NOTHING-1",
                        LocalDate.now(),
                        money("11000.00"),
                        money("0.00"),
                        order.getId(),
                        grn.getId(),
                        List.of(
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        FLOUR, "FLOUR-2KG", money("100"), money("95.00")),
                                new SupplierInvoiceService.InvoiceLineRequest(
                                        SUGAR, "SUGAR-1KG", money("50"), money("30.00"))));
        UUID invoiceId = matched.invoice().getId();

        assertThatThrownBy(() -> invoiceService.acceptException(invoiceId, "No reason needed"))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("nothing to accept");
    }

    @Test
    void thesSameInvoiceNumberCannotBeRecordedTwiceForOneSupplier() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote grn = receiveInFull(order);

        invoiceService.record(
                order.getSupplier().getId(),
                "INV-DUP-1",
                LocalDate.now(),
                money("11000.00"),
                money("0.00"),
                order.getId(),
                grn.getId(),
                List.of(
                        new SupplierInvoiceService.InvoiceLineRequest(
                                FLOUR, "FLOUR-2KG", money("100"), money("95.00"))));

        UUID supplierId = order.getSupplier().getId();
        UUID orderId = order.getId();
        UUID grnId = grn.getId();

        assertThatThrownBy(
                        () ->
                                invoiceService.record(
                                        supplierId,
                                        "INV-DUP-1",
                                        LocalDate.now(),
                                        money("11000.00"),
                                        money("0.00"),
                                        orderId,
                                        grnId,
                                        List.of()))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("already been recorded");
    }

    @Test
    @DisplayName(
            "an invoice against an unposted receipt is refused rather than matched against nothing")
    void anUnpostedReceiptCannotBeMatched() {
        PurchaseOrder order = givenSentOrder();
        GoodsReceivedNote draft =
                receiptService.draft(
                        order.getSupplier().getId(),
                        BRANCH,
                        order.getId(),
                        "DN-DRAFT",
                        null,
                        null,
                        null,
                        null,
                        List.of(receiptLine(FLOUR, "FLOUR-2KG", "100", "95.00", "B-1")));

        UUID supplierId = order.getSupplier().getId();
        UUID draftId = draft.getId();

        assertThatThrownBy(
                        () ->
                                invoiceService.record(
                                        supplierId,
                                        "INV-UNPOSTED",
                                        LocalDate.now(),
                                        money("9500.00"),
                                        money("0.00"),
                                        null,
                                        draftId,
                                        List.of()))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("has not been posted");
    }

    @Test
    @DisplayName(
            "an invoice with nothing to match against stays pending rather than claiming a match")
    void anInvoiceWithNoEvidenceStaysPending() {
        Supplier supplier = givenSupplier("SUP-PEND", "Paperwork Later Ltd");

        SupplierInvoiceService.MatchedInvoice matched =
                invoiceService.record(
                        supplier.getId(),
                        "INV-PENDING",
                        LocalDate.now(),
                        money("5000.00"),
                        money("0.00"),
                        null,
                        null,
                        List.of());

        assertThat(matched.invoice().getMatchStatus()).isEqualTo(InvoiceMatchStatus.PENDING);
        assertThat(matched.invoice().getMatchNotes()).contains("No goods receipt");
    }

    // --- helpers ----------------------------------------------------------------

    private PurchaseOrder draftOrder(Supplier supplier) {
        return orderService.create(
                supplier.getId(),
                BRANCH,
                LocalDate.now().plusDays(7),
                "Weekly order",
                List.of(
                        new PurchaseOrderService.OrderLineRequest(
                                FLOUR,
                                "FLOUR-2KG",
                                "Flour 2kg",
                                money("100"),
                                money("95.00"),
                                money("0")),
                        // Flour is zero-rated and sugar is not, so the order exercises both.
                        new PurchaseOrderService.OrderLineRequest(
                                SUGAR,
                                "SUGAR-1KG",
                                "Sugar 1kg",
                                money("50"),
                                money("30.00"),
                                money("0.16"))));
    }

    private PurchaseOrder givenSentOrder() {
        Supplier supplier = givenSupplier(uniqueCode(), "Wholesale Foods");
        PurchaseOrder order = draftOrder(supplier);
        orderService.submit(order.getId());
        orderService.approve(order.getId());
        return orderService.markSent(order.getId());
    }

    private GoodsReceivedNote receiveInFull(PurchaseOrder order) {
        GoodsReceivedNote grn =
                receiptService.draft(
                        order.getSupplier().getId(),
                        BRANCH,
                        order.getId(),
                        "DN-FULL",
                        null,
                        null,
                        null,
                        null,
                        List.of(
                                receiptLine(FLOUR, "FLOUR-2KG", "100", "95.00", "B-FLOUR-1"),
                                receiptLine(SUGAR, "SUGAR-1KG", "50", "30.00", "B-SUGAR-1")));
        return receiptService.post(grn.getId());
    }

    /** Supplier codes are VARCHAR(30), so a full UUID does not fit. */
    private static String uniqueCode() {
        return "SUP-" + UUID.randomUUID().toString().substring(0, 8);
    }

    private static GoodsReceiptService.ReceiptLineRequest receiptLine(
            UUID productId, String sku, String quantity, String unitCost, String batch) {
        return new GoodsReceiptService.ReceiptLineRequest(
                productId,
                sku,
                sku,
                money(quantity),
                null,
                null,
                money(unitCost),
                batch,
                LocalDate.now().plusMonths(6));
    }
}
