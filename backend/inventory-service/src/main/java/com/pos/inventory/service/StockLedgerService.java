package com.pos.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pos.common.correlation.CorrelationId;
import com.pos.inventory.domain.MovementType;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.StockMovement;
import com.pos.inventory.repository.StockItemRepository;
import com.pos.inventory.repository.StockMovementRepository;

import lombok.RequiredArgsConstructor;

/**
 * The only way stock changes.
 *
 * <p>Writes the ledger entry and applies it to the cached quantity in one step, so the two cannot
 * diverge. Everything that moves stock goes through here; nothing anywhere else writes {@code
 * quantity_on_hand} directly. That single rule is what makes the reconciliation check meaningful -
 * if the ledger and the cache ever disagree, some code path broke this rule, and the check says so
 * rather than a stock take discovering it months later.
 *
 * <p>Runs in the caller's transaction and refuses to run outside one, because a movement recorded
 * without the batch change it describes, or the other way round, is worse than either alone.
 */
@Service
@RequiredArgsConstructor
public class StockLedgerService {

    private final StockMovementRepository movements;
    private final StockItemRepository items;

    /** Everything a ledger entry needs to be explainable a year later. */
    public record MovementContext(
            MovementType type,
            String reasonCode,
            String referenceType,
            UUID referenceId,
            UUID actorId,
            Instant occurredAt) {

        public static MovementContext of(
                MovementType type, String referenceType, UUID referenceId) {
            return new MovementContext(type, null, referenceType, referenceId, null, Instant.now());
        }

        public MovementContext withReason(String reason) {
            return new MovementContext(
                    type, reason, referenceType, referenceId, actorId, occurredAt);
        }

        public MovementContext by(UUID actor) {
            return new MovementContext(
                    type, reasonCode, referenceType, referenceId, actor, occurredAt);
        }
    }

    /**
     * Records a movement and applies it.
     *
     * @param signedQuantity negative takes stock away, positive puts it on
     * @param batch the batch affected, or null when the movement is not batch-specific - a
     *     shortfall on a sale, for instance, where there was no batch left to take it from
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockMovement record(
            StockItem item, StockBatch batch, BigDecimal signedQuantity, MovementContext context) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "StockLedgerService.record() was called outside a transaction. The ledger entry"
                            + " and the quantity it describes must commit together.");
        }
        if (signedQuantity == null || signedQuantity.signum() == 0) {
            throw new IllegalArgumentException("A movement of zero is not a movement");
        }

        StockMovement movement = new StockMovement();
        movement.setStockItemId(item.getId());
        movement.setBatchId(batch == null ? null : batch.getId());
        movement.setBranchId(item.getBranchId());
        movement.setProductId(item.getProductId());
        movement.setType(context.type());
        movement.setQuantity(signedQuantity);
        movement.setReasonCode(context.reasonCode());
        movement.setReferenceType(context.referenceType());
        movement.setReferenceId(context.referenceId());
        movement.setActorId(context.actorId());
        movement.setCorrelationId(CorrelationId.get());
        movement.setOccurredAt(context.occurredAt() == null ? Instant.now() : context.occurredAt());

        if (batch != null) {
            movement.setUnitCost(batch.getUnitCost());
            movement.setCurrency(batch.getCurrency());
        }

        movements.save(movement);

        // Cache and ledger move together, in the same transaction, from the same call.
        item.applyMovement(signedQuantity, movement.getOccurredAt());
        items.save(item);

        return movement;
    }

    /**
     * What the ledger says an item holds.
     *
     * <p>Used by the reconciliation check and by anything that would rather be slow and right than
     * fast and possibly stale.
     */
    @Transactional(readOnly = true)
    public BigDecimal ledgerQuantityFor(UUID stockItemId) {
        BigDecimal sum = movements.sumQuantityFor(stockItemId);
        return sum == null ? BigDecimal.ZERO : sum;
    }
}
