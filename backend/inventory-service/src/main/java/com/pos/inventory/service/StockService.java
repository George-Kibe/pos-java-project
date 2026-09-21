package com.pos.inventory.service;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.events.inventory.StockDeductedPayload;
import com.pos.inventory.domain.MovementType;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.fefo.Allocation;
import com.pos.inventory.domain.fefo.AllocationResult;
import com.pos.inventory.domain.fefo.AvailableBatch;
import com.pos.inventory.domain.fefo.FefoAllocator;
import com.pos.inventory.messaging.InventoryEventPublisher;
import com.pos.inventory.repository.StockBatchRepository;
import com.pos.inventory.repository.StockItemRepository;

import lombok.RequiredArgsConstructor;

/** Receiving, selling and returning stock. */
@Service
@RequiredArgsConstructor
public class StockService {

    private static final Logger log = LoggerFactory.getLogger(StockService.class);

    private final StockItemRepository items;
    private final StockBatchRepository batches;
    private final StockLedgerService ledger;
    private final InventoryEventPublisher events;

    /** One product's worth of a delivery. */
    public record ReceiptLine(
            UUID productId,
            String sku,
            BigDecimal quantity,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal unitCost,
            String currency) {}

    /** One product's worth of a sale. */
    public record SaleLine(UUID productId, String sku, BigDecimal quantity, String batchNumber) {}

    /** One product coming back. */
    public record ReturnLine(
            UUID productId,
            String sku,
            BigDecimal quantity,
            boolean resaleable,
            String batchNumber) {}

    // --- receiving --------------------------------------------------------------

    /**
     * Puts a delivery on the shelf.
     *
     * <p>A repeated batch number for the same item adds to the existing batch rather than creating
     * a second one, because two rows for the same physical carton would split its expiry and cost
     * across two records that can then disagree.
     */
    @Transactional
    public void receive(
            UUID branchId, UUID referenceId, String referenceType, List<ReceiptLine> lines) {
        for (ReceiptLine line : lines) {
            StockItem item = findOrCreateItem(line.productId(), branchId, line.sku());

            StockBatch batch =
                    batches.findByStockItemIdAndBatchNumber(item.getId(), line.batchNumber())
                            .orElseGet(
                                    () -> {
                                        StockBatch created =
                                                new StockBatch(
                                                        item,
                                                        line.batchNumber(),
                                                        line.expiryDate(),
                                                        BigDecimal.ZERO,
                                                        line.unitCost(),
                                                        line.currency() == null
                                                                ? "KES"
                                                                : line.currency());
                                        created.setSourceType(referenceType);
                                        created.setSourceId(referenceId);
                                        return batches.save(created);
                                    });

            batch.add(line.quantity());
            // The latest delivery sets the cost, which is what the next sale out of it is worth.
            batch.setUnitCost(line.unitCost());
            batches.save(batch);

            ledger.record(
                    item,
                    batch,
                    line.quantity(),
                    StockLedgerService.MovementContext.of(
                            MovementType.RECEIPT, referenceType, referenceId));
        }
    }

    // --- selling ----------------------------------------------------------------

    /**
     * Takes a sale off the shelf, oldest-dated stock first.
     *
     * <p>A line that cannot be covered is still deducted in full. By the time this service hears
     * about a sale the customer has left with the goods, so recording less than was taken would put
     * the books further from reality. The uncovered part becomes a batch-less movement, the on-hand
     * figure goes negative, and an alert says so.
     */
    @Transactional
    public void deductForSale(UUID saleId, UUID branchId, List<SaleLine> lines) {
        List<StockDeductedPayload.DeductedLine> deducted = new ArrayList<>();

        for (SaleLine line : lines) {
            StockItem item = findOrCreateItem(line.productId(), branchId, line.sku());

            List<StockBatch> sellable = batches.findSellable(item.getId());
            List<AvailableBatch> available =
                    sellable.stream().map(StockBatch::toAvailable).toList();
            AllocationResult allocation = FefoAllocator.allocate(available, line.quantity());

            List<StockDeductedPayload.BatchAllocation> allocations = new ArrayList<>();

            for (Allocation part : allocation.allocations()) {
                StockBatch batch =
                        sellable.stream()
                                .filter(candidate -> candidate.getId().equals(part.batchId()))
                                .findFirst()
                                .orElseThrow();

                batch.consume(part.quantity());
                batches.save(batch);

                ledger.record(
                        item,
                        batch,
                        part.quantity().negate(),
                        StockLedgerService.MovementContext.of(MovementType.SALE, "Sale", saleId));

                allocations.add(
                        new StockDeductedPayload.BatchAllocation(
                                batch.getId(),
                                batch.getBatchNumber(),
                                part.quantity(),
                                batch.getUnitCost()));
            }

            if (allocation.isShort()) {
                // No batch to attribute it to, but the stock left the shop all the same.
                ledger.record(
                        item,
                        null,
                        allocation.shortfall().negate(),
                        StockLedgerService.MovementContext.of(MovementType.SALE, "Sale", saleId)
                                .withReason("UNTRACKED_SHORTFALL"));

                log.warn(
                        "Sale {} took {} of product {} at branch {} that inventory did not have",
                        saleId,
                        allocation.shortfall(),
                        line.productId(),
                        branchId);
                events.negativeStock(item, line.quantity(), "SALE", saleId);
            }

            deducted.add(
                    new StockDeductedPayload.DeductedLine(
                            line.productId(),
                            line.quantity(),
                            item.getQuantityOnHand(),
                            allocations));

            raiseLowStockIfNeeded(item);
        }

        events.stockDeducted(saleId, branchId, deducted);
    }

    // --- returns ----------------------------------------------------------------

    /**
     * Puts returned goods back, or writes them off.
     *
     * <p>Only resaleable goods go back on the shelf. Returning a damaged item to sellable stock is
     * how a shop ends up selling something it already knows is broken, so the flag from the till is
     * honoured rather than second-guessed.
     */
    @Transactional
    public void restockFromReturn(UUID returnId, UUID branchId, List<ReturnLine> lines) {
        for (ReturnLine line : lines) {
            StockItem item = findOrCreateItem(line.productId(), branchId, line.sku());

            if (!line.resaleable()) {
                // Recorded in, then straight back out as a write-off, so both halves are
                // visible: the goods came back, and then they were destroyed. A single netted-off
                // entry would hide the return from the shrinkage report entirely.
                ledger.record(
                        item,
                        null,
                        line.quantity(),
                        StockLedgerService.MovementContext.of(
                                MovementType.RETURN, "Return", returnId));
                ledger.record(
                        item,
                        null,
                        line.quantity().negate(),
                        StockLedgerService.MovementContext.of(
                                        MovementType.WRITE_OFF, "Return", returnId)
                                .withReason("DAMAGE"));
                continue;
            }

            String batchNumber =
                    line.batchNumber() == null || line.batchNumber().isBlank()
                            ? "RETURN-" + returnId
                            : line.batchNumber();

            StockBatch batch =
                    batches.findByStockItemIdAndBatchNumber(item.getId(), batchNumber)
                            .orElseGet(
                                    () -> {
                                        StockBatch created =
                                                new StockBatch(
                                                        item,
                                                        batchNumber,
                                                        null,
                                                        BigDecimal.ZERO,
                                                        BigDecimal.ZERO,
                                                        "KES");
                                        created.setSourceType("Return");
                                        created.setSourceId(returnId);
                                        return batches.save(created);
                                    });

            batch.add(line.quantity());
            batches.save(batch);

            ledger.record(
                    item,
                    batch,
                    line.quantity(),
                    StockLedgerService.MovementContext.of(MovementType.RETURN, "Return", returnId));
        }
    }

    // --- helpers ----------------------------------------------------------------

    /**
     * The stock row for a product at a branch, created on first use.
     *
     * <p>Created rather than refused: a sale of something inventory has never seen is exactly the
     * case where a record is most needed, and refusing it would lose the fact that stock left.
     */
    @Transactional
    public StockItem findOrCreateItem(UUID productId, UUID branchId, String sku) {
        return items.findByProductIdAndBranchId(productId, branchId)
                .orElseGet(
                        () -> {
                            StockItem created = new StockItem(productId, branchId);
                            created.setSku(sku);
                            return items.save(created);
                        });
    }

    /**
     * Refreshes the product details cached on every stock row for a product.
     *
     * <p>Catalog owns these; they are copied here so listing a branch's stock does not call another
     * service once per line, and because a join across schemas is not available in any case.
     *
     * @return how many rows were refreshed
     */
    @Transactional
    public int refreshProductDetails(
            UUID productId, String sku, String name, String unitOfMeasure) {
        List<StockItem> affected = items.findByProductId(productId);
        for (StockItem item : affected) {
            item.setSku(sku);
            item.setProductName(name);
            item.setUnitOfMeasure(unitOfMeasure);
            items.save(item);
        }
        return affected.size();
    }

    /**
     * Sets the reorder point that the low-stock alert fires on.
     *
     * <p>Here rather than in the controller because it mutates a managed entity: done outside a
     * transaction the change depends on whether something else happens to flush it.
     */
    @Transactional
    public StockItem setReorderPoint(
            UUID productId, UUID branchId, BigDecimal reorderPoint, BigDecimal reorderQuantity) {
        StockItem item = require(productId, branchId);
        item.setReorderPoint(reorderPoint);
        item.setReorderQuantity(reorderQuantity);
        return items.save(item);
    }

    @Transactional(readOnly = true)
    public StockItem require(UUID productId, UUID branchId) {
        return items.findByProductIdAndBranchId(productId, branchId)
                .orElseThrow(() -> Errors.NotFoundException.of("Stock item", productId));
    }

    private void raiseLowStockIfNeeded(StockItem item) {
        if (item.isBelowReorderPoint()) {
            events.lowStock(item);
        }
    }
}
