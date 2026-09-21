package com.pos.purchasing.messaging;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Component;

import com.pos.common.correlation.CorrelationId;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.purchasing.GoodsReceivedPayload;
import com.pos.events.purchasing.PoApprovedPayload;
import com.pos.events.purchasing.SupplierCostChangedPayload;
import com.pos.messaging.outbox.OutboxRecorder;
import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.domain.GrnLine;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.SupplierProduct;

import lombok.RequiredArgsConstructor;

/**
 * Announces what purchasing did.
 *
 * <p>Through the outbox, in the caller's transaction, so inventory hears about a delivery exactly
 * when the receipt is committed. Publishing directly would let stock appear on the shelf for a
 * receipt that then rolled back - the one failure mode that is worse than a slow event.
 */
@Component
@RequiredArgsConstructor
public class PurchasingEventPublisher {

    private final OutboxRecorder outbox;

    public void poApproved(PurchaseOrder order) {
        List<PoApprovedPayload.ApprovedLine> lines =
                order.getLines().stream()
                        .map(
                                line ->
                                        new PoApprovedPayload.ApprovedLine(
                                                line.getProductId(),
                                                line.getSku(),
                                                line.getProductName(),
                                                line.getQuantityOrdered(),
                                                line.getUnitCost(),
                                                line.getLineTotal()))
                        .toList();

        outbox.record(
                Topics.PURCHASING_PO_APPROVED,
                "PurchaseOrder",
                order.getId(),
                EventEnvelope.<PoApprovedPayload>builder()
                        .topic(Topics.PURCHASING_PO_APPROVED)
                        .correlationId(CorrelationId.get())
                        .branchId(order.getBranchId())
                        .actorId(order.getApprovedBy())
                        .payload(
                                new PoApprovedPayload(
                                        order.getId(),
                                        order.getOrderNumber(),
                                        order.getSupplier().getId(),
                                        order.getSupplier().getName(),
                                        order.getBranchId(),
                                        order.getApprovedBy(),
                                        order.getApprovedAt(),
                                        order.getExpectedDeliveryDate(),
                                        lines,
                                        order.getNetTotal(),
                                        order.getTaxTotal(),
                                        order.getGrandTotal(),
                                        order.getCurrency()))
                        .build());
    }

    /**
     * The delivery, for inventory to put on the shelf.
     *
     * <p>Carries the <b>landed</b> unit cost rather than the invoice price, because that is what
     * the stock is worth once freight and duty are counted. Rejected quantities are excluded: goods
     * refused at the door never become sellable stock.
     */
    public void goodsReceived(GoodsReceivedNote grn) {
        List<GoodsReceivedPayload.ReceivedLine> lines =
                grn.getLines().stream()
                        .filter(line -> line.quantityAccepted().signum() > 0)
                        .map(
                                line ->
                                        new GoodsReceivedPayload.ReceivedLine(
                                                line.getProductId(),
                                                line.getSku(),
                                                line.quantityAccepted(),
                                                batchNumberFor(grn, line),
                                                line.getExpiryDate(),
                                                landedCostOf(line),
                                                line.getCurrency()))
                        .toList();

        outbox.record(
                Topics.PURCHASING_GOODS_RECEIVED,
                "GoodsReceivedNote",
                grn.getId(),
                EventEnvelope.<GoodsReceivedPayload>builder()
                        .topic(Topics.PURCHASING_GOODS_RECEIVED)
                        .correlationId(CorrelationId.get())
                        .branchId(grn.getBranchId())
                        .actorId(grn.getPostedBy())
                        .payload(
                                new GoodsReceivedPayload(
                                        grn.getId(),
                                        grn.getPurchaseOrder() == null
                                                ? null
                                                : grn.getPurchaseOrder().getId(),
                                        grn.getBranchId(),
                                        grn.getSupplier().getId(),
                                        grn.getPostedBy(),
                                        grn.getPostedAt(),
                                        lines))
                        .build());
    }

    public void supplierCostChanged(
            SupplierProduct product, BigDecimal previous, String sourceType, UUID sourceId) {

        BigDecimal current =
                product.getLastUnitCost() != null
                        ? product.getLastUnitCost()
                        : product.getAgreedUnitCost();

        outbox.record(
                Topics.PURCHASING_SUPPLIER_COST_CHANGED,
                "SupplierProduct",
                product.getId(),
                EventEnvelope.<SupplierCostChangedPayload>builder()
                        .topic(Topics.PURCHASING_SUPPLIER_COST_CHANGED)
                        .correlationId(CorrelationId.get())
                        .payload(
                                new SupplierCostChangedPayload(
                                        product.getSupplier().getId(),
                                        product.getSupplier().getName(),
                                        product.getProductId(),
                                        product.getSku(),
                                        previous,
                                        current,
                                        product.getCurrency(),
                                        sourceType,
                                        sourceId,
                                        Instant.now()))
                        .build());
    }

    /**
     * A batch number for stock that arrived without one.
     *
     * <p>Untracked stock has to go somewhere, and putting it in a batch named after the receipt
     * keeps it traceable to a delivery. Lumping it into a shared "no batch" bucket would mix two
     * deliveries whose costs and ages differ.
     */
    private static String batchNumberFor(GoodsReceivedNote grn, GrnLine line) {
        return line.getBatchNumber() == null || line.getBatchNumber().isBlank()
                ? grn.getGrnNumber() + "-L" + line.getLineNumber()
                : line.getBatchNumber();
    }

    private static BigDecimal landedCostOf(GrnLine line) {
        return line.getLandedUnitCost() != null ? line.getLandedUnitCost() : line.getUnitCost();
    }
}
