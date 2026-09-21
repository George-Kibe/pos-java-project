package com.pos.inventory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.inventory.domain.MovementType;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.fefo.Allocation;
import com.pos.inventory.domain.fefo.AllocationResult;
import com.pos.inventory.domain.fefo.FefoAllocator;
import com.pos.inventory.repository.StockBatchRepository;

import lombok.RequiredArgsConstructor;

/**
 * Takes a quantity out of an item's batches, oldest-dated first, writing the ledger as it goes.
 *
 * <p>Shared by everything that removes stock for a reason other than a sale - write-offs, transfers
 * out, count shortfalls - so they all deplete in the same order and all leave the same trail.
 * Without this each of them would grow its own slightly different version of FEFO.
 */
@Service
@RequiredArgsConstructor
public class BatchConsumer {

    private final StockBatchRepository batches;
    private final StockLedgerService ledger;

    /**
     * @return what could not be covered by any batch
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public BigDecimal consume(
            StockItem item, BigDecimal quantity, StockLedgerService.MovementContext context) {

        List<StockBatch> sellable = batches.findSellable(item.getId());
        AllocationResult allocation =
                FefoAllocator.allocate(
                        sellable.stream().map(StockBatch::toAvailable).toList(), quantity);

        for (Allocation part : allocation.allocations()) {
            StockBatch batch =
                    sellable.stream()
                            .filter(candidate -> candidate.getId().equals(part.batchId()))
                            .findFirst()
                            .orElseThrow();
            batch.consume(part.quantity());
            batches.save(batch);
            ledger.record(item, batch, part.quantity().negate(), context);
        }

        if (allocation.isShort()) {
            // Recorded without a batch: the stock is gone whether or not there was a batch to
            // take it from, and hiding that would leave the ledger disagreeing with reality.
            ledger.record(item, null, allocation.shortfall().negate(), context);
        }

        return allocation.shortfall();
    }

    /**
     * Adds stock that has no delivery behind it - a count surplus, a positive adjustment.
     *
     * <p>Given a batch of its own rather than recorded batch-less, because found stock is real
     * stock that will be sold, and it needs a cost and a place in the expiry order like anything
     * else.
     */
    @Transactional(propagation = Propagation.MANDATORY)
    public StockBatch addUntrackedStock(
            StockItem item,
            BigDecimal quantity,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal unitCost,
            StockLedgerService.MovementContext context) {

        StockBatch batch =
                batches.findByStockItemIdAndBatchNumber(item.getId(), batchNumber)
                        .orElseGet(
                                () ->
                                        batches.save(
                                                new StockBatch(
                                                        item,
                                                        batchNumber,
                                                        expiryDate,
                                                        BigDecimal.ZERO,
                                                        unitCost == null
                                                                ? BigDecimal.ZERO
                                                                : unitCost,
                                                        "KES")));

        batch.add(quantity);
        batches.save(batch);
        ledger.record(item, batch, quantity, context);
        return batch;
    }

    /** Where a positive adjustment's stock should sit when nothing better is known. */
    public static String syntheticBatchNumber(String prefix, Object reference) {
        return prefix + "-" + reference;
    }

    /** What an item's batches actually hold, regardless of the cached figure. */
    @Transactional(propagation = Propagation.SUPPORTS, readOnly = true)
    public BigDecimal batchQuantityFor(StockItem item) {
        return FefoAllocator.availableIn(
                batches.findSellable(item.getId()).stream().map(StockBatch::toAvailable).toList());
    }

    /** Exposed so callers can note the movement type they are consuming for. */
    public static StockLedgerService.MovementContext writeOff(
            String referenceType, java.util.UUID id) {
        return StockLedgerService.MovementContext.of(MovementType.WRITE_OFF, referenceType, id);
    }
}
