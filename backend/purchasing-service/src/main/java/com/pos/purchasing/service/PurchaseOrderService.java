package com.pos.purchasing.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.PurchaseOrderStatus;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.messaging.PurchasingEventPublisher;
import com.pos.purchasing.repository.PurchaseOrderRepository;

import lombok.RequiredArgsConstructor;

/**
 * The life of a purchase order.
 *
 * <p>Every transition goes through {@link #transition}, so the state machine cannot be bypassed by
 * a new endpoint that sets a status directly. The transitions that must not happen are the point:
 * an order that can return to DRAFT after approval is an order whose lines can be edited after the
 * spend was authorised.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PurchaseOrderService {

    private final PurchaseOrderRepository orders;
    private final SupplierService suppliers;
    private final DocumentNumberService numbers;
    private final PurchasingEventPublisher events;

    /** One product on a draft order. */
    public record OrderLineRequest(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal taxRate) {}

    public Page<PurchaseOrder> list(UUID branchId, PurchaseOrderStatus status, Pageable pageable) {
        return status == null
                ? orders.findByBranchId(branchId, pageable)
                : orders.findByBranchIdAndStatus(branchId, status, pageable);
    }

    public PurchaseOrder require(UUID id) {
        return orders.findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Purchase order", id));
    }

    @Transactional
    public PurchaseOrder create(
            UUID supplierId,
            UUID branchId,
            LocalDate expectedDeliveryDate,
            String notes,
            List<OrderLineRequest> lines) {

        Supplier supplier = suppliers.require(supplierId);
        if (!supplier.getStatus().canAcceptNewOrders()) {
            throw new Errors.BusinessRuleException(
                    "supplier.not_orderable",
                    "Supplier %s is %s and cannot take new orders"
                            .formatted(supplier.getName(), supplier.getStatus()));
        }
        if (lines == null || lines.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "purchase_order.no_lines", "A purchase order needs at least one line");
        }

        PurchaseOrder order =
                new PurchaseOrder(numbers.nextPurchaseOrderNumber(), supplier, branchId);
        order.setExpectedDeliveryDate(expectedDeliveryDate);
        order.setNotes(notes);
        order.setOrderDate(LocalDate.now());

        for (OrderLineRequest line : lines) {
            order.addLine(
                    line.productId(),
                    line.sku(),
                    line.productName(),
                    line.quantity(),
                    line.unitCost(),
                    line.taxRate());
        }
        order.recalculateTotals();
        return orders.save(order);
    }

    /**
     * Replaces a draft order's lines.
     *
     * <p>Only while DRAFT. Once submitted the document is under review and its figures have to stop
     * moving, or an approver is signing something other than what they read.
     */
    @Transactional
    public PurchaseOrder replaceLines(UUID id, List<OrderLineRequest> lines) {
        PurchaseOrder order = require(id);
        if (!order.getStatus().isEditable()) {
            throw new Errors.BusinessRuleException(
                    "purchase_order.not_editable",
                    "A %s order cannot be edited".formatted(order.getStatus()));
        }
        if (lines == null || lines.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "purchase_order.no_lines", "A purchase order needs at least one line");
        }

        order.getLines().clear();
        // Flushed before the new lines are added. Hibernate orders inserts before the deletes that
        // orphan removal queues, so line 1 of the replacement hits the unique (order, line_number)
        // index while line 1 of the original is still in the table.
        orders.saveAndFlush(order);

        for (OrderLineRequest line : lines) {
            order.addLine(
                    line.productId(),
                    line.sku(),
                    line.productName(),
                    line.quantity(),
                    line.unitCost(),
                    line.taxRate());
        }
        order.recalculateTotals();
        return orders.save(order);
    }

    @Transactional
    public PurchaseOrder submit(UUID id) {
        PurchaseOrder order = transition(require(id), PurchaseOrderStatus.SUBMITTED);
        order.setSubmittedBy(currentActor());
        order.setSubmittedAt(Instant.now());
        return orders.save(order);
    }

    /** Sends a submitted order back to the buyer for changes. */
    @Transactional
    public PurchaseOrder returnToDraft(UUID id) {
        return orders.save(transition(require(id), PurchaseOrderStatus.DRAFT));
    }

    /**
     * Authorises the spend.
     *
     * <p>{@code approvedTotal} freezes what was authorised, and the order can no longer be edited,
     * so the two figures cannot drift apart afterwards.
     */
    @Transactional
    public PurchaseOrder approve(UUID id) {
        PurchaseOrder order = transition(require(id), PurchaseOrderStatus.APPROVED);
        order.setApprovedBy(currentActor());
        order.setApprovedAt(Instant.now());
        order.setApprovedTotal(order.getGrandTotal());
        PurchaseOrder saved = orders.save(order);
        events.poApproved(saved);
        return saved;
    }

    @Transactional
    public PurchaseOrder markSent(UUID id) {
        PurchaseOrder order = transition(require(id), PurchaseOrderStatus.SENT);
        order.setSentAt(Instant.now());
        return orders.save(order);
    }

    @Transactional
    public PurchaseOrder close(UUID id) {
        PurchaseOrder order = transition(require(id), PurchaseOrderStatus.CLOSED);
        order.setClosedAt(Instant.now());
        return orders.save(order);
    }

    /**
     * Abandons an order.
     *
     * <p>A status change with a reason, never a delete. The order may already have been sent to a
     * supplier, and "we cancelled it" is a fact somebody will need to prove.
     */
    @Transactional
    public PurchaseOrder cancel(UUID id, String reason) {
        PurchaseOrder order = require(id);
        if (order.getLines().stream().anyMatch(line -> line.getQuantityReceived().signum() > 0)) {
            throw new Errors.BusinessRuleException(
                    "purchase_order.partly_delivered",
                    "Goods have already been received against this order; close it instead");
        }
        PurchaseOrder cancelled = transition(order, PurchaseOrderStatus.CANCELLED);
        cancelled.setCancelledAt(Instant.now());
        cancelled.setCancellationReason(reason);
        return orders.save(cancelled);
    }

    /**
     * Brings the order's status into line with what has been delivered.
     *
     * <p>Called by the receipt service after a GRN posts, because how much has arrived is the only
     * thing that decides between SENT, PARTIALLY_RECEIVED and RECEIVED.
     */
    @Transactional
    public void refreshReceiptStatus(PurchaseOrder order) {
        if (order.isFullyReceived()) {
            transition(order, PurchaseOrderStatus.RECEIVED);
        } else if (order.isPartiallyReceived()) {
            transition(order, PurchaseOrderStatus.PARTIALLY_RECEIVED);
        }
        orders.save(order);
    }

    /** Who is doing this, taken from the verified token rather than from the request. */
    private static UUID currentActor() {
        return AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null);
    }

    /**
     * The one place a status changes.
     *
     * @throws Errors.ConflictException if the move is not one the state machine allows
     */
    private PurchaseOrder transition(PurchaseOrder order, PurchaseOrderStatus next) {
        if (!order.getStatus().canTransitionTo(next)) {
            throw new Errors.ConflictException(
                    "purchase_order.illegal_transition",
                    "A %s order cannot become %s".formatted(order.getStatus(), next));
        }
        order.setStatus(next);
        return order;
    }
}
