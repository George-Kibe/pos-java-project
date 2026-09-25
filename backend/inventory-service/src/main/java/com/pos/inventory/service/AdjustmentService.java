package com.pos.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.inventory.AdjustmentPostedPayload;
import com.pos.inventory.domain.AdjustmentReason;
import com.pos.inventory.domain.MovementType;
import com.pos.inventory.domain.StockAdjustment;
import com.pos.inventory.domain.StockAdjustmentLine;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.repository.StockAdjustmentRepository;
import com.pos.messaging.outbox.OutboxRecorder;

import lombok.RequiredArgsConstructor;

/**
 * Deliberate corrections to stock.
 *
 * <p>Drafted, then posted. Posting is what writes movements, so an adjustment can be prepared and
 * reviewed before it changes anything - which matters because this is the one way stock changes
 * with no sale, delivery or count behind it, and therefore the one most worth a second pair of
 * eyes.
 */
@Service
@RequiredArgsConstructor
public class AdjustmentService {

    private final StockAdjustmentRepository adjustments;
    private final StockService stock;
    private final BatchConsumer batchConsumer;
    private final OutboxRecorder outbox;

    /**
     * @param quantityDelta signed: negative writes stock off, positive puts it on
     */
    public record AdjustmentLineRequest(
            UUID productId, String sku, BigDecimal quantityDelta, String notes) {}

    @Transactional(readOnly = true)
    public Page<StockAdjustment> list(UUID branchId, Pageable pageable) {
        Page<StockAdjustment> page =
                adjustments.findByBranchIdOrderByCreatedAtDesc(branchId, pageable);
        page.forEach(AdjustmentService::loadForResponse);
        return page;
    }

    @Transactional(readOnly = true)
    public StockAdjustment get(UUID id) {
        // post and cancel start here too, so their responses are covered as well.
        return loadForResponse(
                adjustments
                        .findById(id)
                        .orElseThrow(() -> Errors.NotFoundException.of("Adjustment", id)));
    }

    /**
     * Loads, inside the transaction, what an adjustment response reads after it: each line's stock
     * item. The controller maps with no session open, and a lazy proxy touched there is a 500.
     */
    private static StockAdjustment loadForResponse(StockAdjustment adjustment) {
        adjustment
                .getLines()
                .forEach(line -> org.hibernate.Hibernate.initialize(line.getStockItem()));
        return adjustment;
    }

    @Transactional
    public StockAdjustment draft(
            UUID branchId,
            AdjustmentReason reason,
            String notes,
            List<AdjustmentLineRequest> lines) {

        if (lines == null || lines.isEmpty()) {
            throw new Errors.BadRequestException(
                    "adjustment.no_lines", "An adjustment needs at least one line.");
        }

        StockAdjustment adjustment = new StockAdjustment(branchId, reason, notes);
        for (AdjustmentLineRequest line : lines) {
            if (line.quantityDelta() == null || line.quantityDelta().signum() == 0) {
                throw new Errors.BadRequestException(
                        "adjustment.zero_line", "An adjustment line of zero changes nothing.");
            }
            StockItem item = stock.findOrCreateItem(line.productId(), branchId, line.sku());
            adjustment.addLine(item, null, line.quantityDelta(), line.notes());
        }
        return adjustments.save(adjustment);
    }

    /**
     * Applies a drafted adjustment.
     *
     * <p>Posting is once and for all: a posted adjustment is never edited, because the movements it
     * wrote are already part of the ledger. A mistake is corrected by a further adjustment in the
     * other direction, which leaves both the error and the correction on record.
     */
    @Transactional
    public StockAdjustment post(UUID adjustmentId) {
        StockAdjustment adjustment = get(adjustmentId);

        if (adjustment.getStatus() != StockAdjustment.Status.DRAFT) {
            throw new Errors.ConflictException(
                    "adjustment.not_draft",
                    "This adjustment is %s and cannot be posted again."
                            .formatted(adjustment.getStatus()));
        }

        UUID actor = AuthenticatedUser.currentUserId();
        List<AdjustmentPostedPayload.AdjustmentLine> posted = new ArrayList<>();

        for (StockAdjustmentLine line : adjustment.getLines()) {
            StockItem item = line.getStockItem();
            BigDecimal delta = line.getQuantityDelta();

            StockLedgerService.MovementContext context =
                    new StockLedgerService.MovementContext(
                            movementTypeFor(adjustment.getReasonCode()),
                            adjustment.getReasonCode().name(),
                            "StockAdjustment",
                            adjustment.getId(),
                            actor,
                            Instant.now());

            // Signed like the quantity: a write-off is worth a negative amount at cost. Stock put
            // back on has no delivery behind it, so no known cost.
            BigDecimal value = BigDecimal.ZERO;
            if (delta.signum() < 0) {
                value =
                        batchConsumer
                                .consumeValued(item, delta.abs(), context)
                                .valueAtCost()
                                .negate();
            } else {
                batchConsumer.addUntrackedStock(
                        item,
                        delta,
                        BatchConsumer.syntheticBatchNumber("ADJ", adjustment.getId()),
                        null,
                        BigDecimal.ZERO,
                        context);
            }

            posted.add(
                    new AdjustmentPostedPayload.AdjustmentLine(
                            item.getProductId(), item.getSku(), delta, null, value, "KES"));
        }

        adjustment.setStatus(StockAdjustment.Status.POSTED);
        adjustment.setPostedAt(Instant.now());
        adjustment.setPostedBy(actor);
        adjustments.save(adjustment);

        outbox.record(
                Topics.INVENTORY_ADJUSTMENT_POSTED,
                "StockAdjustment",
                adjustment.getId(),
                EventEnvelope.<AdjustmentPostedPayload>builder()
                        .topic(Topics.INVENTORY_ADJUSTMENT_POSTED)
                        .correlationId(CorrelationId.get())
                        .branchId(adjustment.getBranchId())
                        .actorId(actor)
                        .payload(
                                new AdjustmentPostedPayload(
                                        adjustment.getId(),
                                        adjustment.getBranchId(),
                                        adjustment.getReasonCode().name(),
                                        actor,
                                        adjustment.getPostedAt(),
                                        adjustment.getNotes(),
                                        posted))
                        .build());

        return adjustment;
    }

    @Transactional
    public StockAdjustment cancel(UUID adjustmentId) {
        StockAdjustment adjustment = get(adjustmentId);
        if (adjustment.getStatus() == StockAdjustment.Status.POSTED) {
            throw new Errors.ConflictException(
                    "adjustment.already_posted",
                    "A posted adjustment cannot be cancelled. Post a correcting adjustment instead.");
        }
        adjustment.setStatus(StockAdjustment.Status.CANCELLED);
        return adjustments.save(adjustment);
    }

    /**
     * Damage and expiry are write-offs rather than plain adjustments.
     *
     * <p>The distinction is what lets a shrinkage report separate "we destroyed this" from "the
     * count was wrong", which are different problems with different answers.
     */
    private static MovementType movementTypeFor(AdjustmentReason reason) {
        return switch (reason) {
            case DAMAGE, EXPIRY, THEFT, SAMPLE -> MovementType.WRITE_OFF;
            case COUNT_CORRECTION, SUPPLIER_RETURN, OTHER -> MovementType.ADJUSTMENT;
        };
    }
}
