package com.pos.purchasing.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.HashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.domain.GrnLine;
import com.pos.purchasing.domain.GrnStatus;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.PurchaseOrderLine;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.cost.AllocationBasis;
import com.pos.purchasing.domain.cost.ChargeableLine;
import com.pos.purchasing.domain.cost.LandedCostAllocator;
import com.pos.purchasing.domain.cost.LineAllocation;
import com.pos.purchasing.messaging.PurchasingEventPublisher;
import com.pos.purchasing.repository.GoodsReceivedNoteRepository;

import lombok.RequiredArgsConstructor;

/**
 * Receiving a delivery.
 *
 * <p>Drafted at the loading bay, then posted. Posting is the moment that matters: it allocates the
 * delivery's freight and duty across the lines, records what the goods really cost, moves the
 * order's status along, and publishes the event that puts stock on the shelf - all in one
 * transaction, so none of it can happen without the rest.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class GoodsReceiptService {

    private static final Logger log = LoggerFactory.getLogger(GoodsReceiptService.class);

    private final GoodsReceivedNoteRepository grns;
    private final SupplierService suppliers;
    private final PurchaseOrderService orders;
    private final DocumentNumberService numbers;
    private final PurchasingEventPublisher events;

    /** One product on a delivery as keyed in. */
    public record ReceiptLineRequest(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityReceived,
            BigDecimal quantityRejected,
            String rejectionReason,
            BigDecimal unitCost,
            String batchNumber,
            LocalDate expiryDate) {}

    public Page<GoodsReceivedNote> list(UUID branchId, Pageable pageable) {
        return grns.findByBranchId(branchId, pageable);
    }

    public GoodsReceivedNote require(UUID id) {
        return grns.findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Goods received note", id));
    }

    /**
     * Starts a receipt, optionally against an order.
     *
     * <p>An order is optional because deliveries genuinely arrive without one, and refusing them
     * would mean the goods are on the shelf with no record of where they came from.
     */
    @Transactional
    public GoodsReceivedNote draft(
            UUID supplierId,
            UUID branchId,
            UUID purchaseOrderId,
            String deliveryNoteRef,
            BigDecimal freightAmount,
            BigDecimal dutyAmount,
            AllocationBasis allocationBasis,
            String notes,
            List<ReceiptLineRequest> lines) {

        if (lines == null || lines.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "grn.no_lines", "A goods receipt needs at least one line");
        }

        Supplier supplier = suppliers.require(supplierId);
        GoodsReceivedNote grn = new GoodsReceivedNote(numbers.nextGrnNumber(), supplier, branchId);
        grn.setDeliveryNoteRef(deliveryNoteRef);
        grn.setReceivedAt(Instant.now());
        grn.setReceivedBy(currentActor());
        grn.setNotes(notes);
        if (freightAmount != null) {
            grn.setFreightAmount(freightAmount);
        }
        if (dutyAmount != null) {
            grn.setDutyAmount(dutyAmount);
        }
        if (allocationBasis != null) {
            grn.setAllocationBasis(allocationBasis);
        }

        Map<UUID, PurchaseOrderLine> orderLines = Map.of();
        if (purchaseOrderId != null) {
            PurchaseOrder order = orders.require(purchaseOrderId);
            requireReceivable(order, branchId);
            grn.setPurchaseOrder(order);
            orderLines = linesByProduct(order);
        }

        for (ReceiptLineRequest request : lines) {
            GrnLine line =
                    grn.addLine(
                            request.productId(),
                            request.sku(),
                            request.productName(),
                            request.quantityReceived(),
                            request.unitCost(),
                            request.batchNumber(),
                            request.expiryDate());

            if (request.quantityRejected() != null) {
                if (request.quantityRejected().compareTo(request.quantityReceived()) > 0) {
                    throw new Errors.BusinessRuleException(
                            "grn.rejected_exceeds_received",
                            "Cannot reject more of %s than was delivered".formatted(request.sku()));
                }
                line.setQuantityRejected(request.quantityRejected());
                line.setRejectionReason(request.rejectionReason());
            }

            PurchaseOrderLine orderLine = orderLines.get(request.productId());
            if (orderLine != null) {
                line.setPurchaseOrderLine(orderLine);
                // Recorded so the discrepancy is visible on the receipt itself, rather than only
                // by comparing two documents later.
                line.setQuantityOrdered(orderLine.getQuantityOrdered());
            }
        }

        grn.setGoodsTotal(goodsTotal(grn));
        grn.setLandedTotal(grn.getGoodsTotal().add(grn.totalCharges()));
        return grns.save(grn);
    }

    /**
     * Commits a receipt.
     *
     * <p>The order of business matters. Landed costs are allocated first, because the event that
     * follows carries them; the order's received quantities are updated next, so its status
     * reflects the delivery; and the event is recorded last, in this same transaction, so stock
     * only appears if all of it held.
     */
    @Transactional
    public GoodsReceivedNote post(UUID id) {
        GoodsReceivedNote grn = require(id);

        if (grn.isPosted()) {
            throw new Errors.ConflictException(
                    "grn.already_posted",
                    "Goods receipt %s has already been posted".formatted(grn.getGrnNumber()));
        }
        if (grn.getStatus() == GrnStatus.CANCELLED) {
            throw new Errors.ConflictException(
                    "grn.cancelled", "A cancelled goods receipt cannot be posted");
        }

        allocateLandedCosts(grn);

        grn.setStatus(GrnStatus.POSTED);
        grn.setPostedAt(Instant.now());
        grn.setPostedBy(currentActor());

        if (grn.getPurchaseOrder() != null) {
            applyToOrder(grn);
        }

        for (GrnLine line : grn.getLines()) {
            suppliers.recordDeliveredCost(
                    grn.getSupplier().getId(),
                    line.getProductId(),
                    line.getUnitCost(),
                    grn.getPostedAt(),
                    "GoodsReceivedNote",
                    grn.getId());
        }

        GoodsReceivedNote posted = grns.save(grn);
        events.goodsReceived(posted);

        log.info(
                "Posted goods receipt {} for supplier {}: {} lines, landed total {}",
                posted.getGrnNumber(),
                posted.getSupplier().getName(),
                posted.getLines().size(),
                posted.getLandedTotal());
        return posted;
    }

    /** Who is doing this, taken from the verified token rather than from the request. */
    private static UUID currentActor() {
        return AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null);
    }

    /** Abandons a draft receipt. A posted one is corrected with a supplier return instead. */
    @Transactional
    public GoodsReceivedNote cancel(UUID id) {
        GoodsReceivedNote grn = require(id);
        if (grn.isPosted()) {
            throw new Errors.ConflictException(
                    "grn.already_posted",
                    "The goods are already in stock; raise a supplier return instead");
        }
        grn.setStatus(GrnStatus.CANCELLED);
        return grns.save(grn);
    }

    /**
     * Spreads freight and duty across the accepted lines.
     *
     * <p>Rejected quantities are excluded from the weighting: freight on goods sent straight back
     * is a cost of the delivery, not of the stock that stayed, and loading it onto the rejected
     * line would bury it in a line that never reaches inventory.
     */
    private void allocateLandedCosts(GoodsReceivedNote grn) {
        List<GrnLine> acceptedLines =
                grn.getLines().stream()
                        .filter(line -> line.quantityAccepted().signum() > 0)
                        .toList();

        if (acceptedLines.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "grn.nothing_accepted",
                    "Every line on this receipt was rejected; there is nothing to post");
        }

        List<ChargeableLine> chargeable =
                acceptedLines.stream()
                        .map(
                                line ->
                                        new ChargeableLine(
                                                line.getId(),
                                                line.quantityAccepted(),
                                                line.acceptedGoodsValue()))
                        .toList();

        List<LineAllocation> allocations =
                LandedCostAllocator.allocate(
                        chargeable, grn.totalCharges(), grn.getAllocationBasis());

        Map<UUID, LineAllocation> byLine = new HashMap<>();
        for (LineAllocation allocation : allocations) {
            byLine.put(allocation.lineId(), allocation);
        }

        BigDecimal goodsTotal = BigDecimal.ZERO;
        BigDecimal landedTotal = BigDecimal.ZERO;

        for (GrnLine line : grn.getLines()) {
            LineAllocation allocation = byLine.get(line.getId());
            if (allocation == null) {
                // Wholly rejected: no charges, and no landed cost, because none of it is stock.
                line.setAllocatedCharges(BigDecimal.ZERO);
                line.setLandedUnitCost(line.getUnitCost());
                continue;
            }
            line.setAllocatedCharges(allocation.allocatedCharges());
            line.setLandedUnitCost(allocation.landedUnitCost());
            goodsTotal = goodsTotal.add(line.acceptedGoodsValue());
            landedTotal = landedTotal.add(allocation.landedValue());
        }

        grn.setGoodsTotal(goodsTotal);
        grn.setLandedTotal(landedTotal);
    }

    /** Records the delivery against the order and moves its status along. */
    private void applyToOrder(GoodsReceivedNote grn) {
        PurchaseOrder order = grn.getPurchaseOrder();
        for (GrnLine line : grn.getLines()) {
            if (line.getPurchaseOrderLine() != null && line.quantityAccepted().signum() > 0) {
                line.getPurchaseOrderLine().receive(line.quantityAccepted());
            }
        }
        orders.refreshReceiptStatus(order);
    }

    private void requireReceivable(PurchaseOrder order, UUID branchId) {
        if (!order.getStatus().acceptsReceipts()) {
            throw new Errors.BusinessRuleException(
                    "purchase_order.not_receivable",
                    "A %s order cannot take a delivery".formatted(order.getStatus()));
        }
        if (!order.getBranchId().equals(branchId)) {
            throw new Errors.BusinessRuleException(
                    "grn.branch_mismatch",
                    "Order %s belongs to another branch".formatted(order.getOrderNumber()));
        }
    }

    private static Map<UUID, PurchaseOrderLine> linesByProduct(PurchaseOrder order) {
        Map<UUID, PurchaseOrderLine> byProduct = new HashMap<>();
        for (PurchaseOrderLine line : order.getLines()) {
            byProduct.put(line.getProductId(), line);
        }
        return byProduct;
    }

    private static BigDecimal goodsTotal(GoodsReceivedNote grn) {
        BigDecimal total = BigDecimal.ZERO;
        List<GrnLine> lines = new ArrayList<>(grn.getLines());
        for (GrnLine line : lines) {
            total = total.add(line.acceptedGoodsValue());
        }
        return total;
    }
}
