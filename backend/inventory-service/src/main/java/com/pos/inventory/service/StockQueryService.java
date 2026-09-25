package com.pos.inventory.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.StockMovement;
import com.pos.inventory.repository.StockBatchRepository;
import com.pos.inventory.repository.StockItemRepository;
import com.pos.inventory.repository.StockMovementRepository;

/**
 * The read side of stock.
 *
 * <p>Separate from {@link StockService} so a controller never reaches into a repository: reads run
 * inside a read-only transaction like every other database access, and the mapping to DTOs stays
 * the only thing the controller does with the result.
 */
@Service
@Transactional(readOnly = true)
public class StockQueryService {

    private final StockItemRepository items;
    private final StockBatchRepository batches;
    private final StockMovementRepository movements;
    private final StockService stock;
    private final java.time.ZoneId shopZone;

    public StockQueryService(
            StockItemRepository items,
            StockBatchRepository batches,
            StockMovementRepository movements,
            StockService stock,
            @org.springframework.beans.factory.annotation.Value(
                            "${pos.inventory.shop-time-zone:Africa/Nairobi}")
                    java.time.ZoneId shopZone) {
        this.items = items;
        this.batches = batches;
        this.movements = movements;
        this.stock = stock;
        this.shopZone = shopZone;
    }

    public Page<StockItem> atBranch(UUID branchId, Pageable pageable) {
        return items.findByBranchId(branchId, pageable);
    }

    public StockItem one(UUID productId, UUID branchId) {
        return stock.require(productId, branchId);
    }

    /** A product's batches in the order they will be sold. */
    public List<StockBatch> batchesOf(UUID productId, UUID branchId) {
        return batches.findSellable(stock.require(productId, branchId).getId());
    }

    public Page<StockMovement> movementsOf(UUID productId, UUID branchId, Pageable pageable) {
        return movements.findByStockItemIdOrderByOccurredAtDesc(
                stock.require(productId, branchId).getId(), pageable);
    }

    /**
     * What will expire at a branch within {@code days} - already expired included, since stock that
     * has passed its date but not been written off is exactly what needs finding.
     */
    public List<StockBatch> expiringAt(UUID branchId, int days) {
        return batches.findExpiringAtBranch(branchId, today().plusDays(days));
    }

    /**
     * Today where the shops are. An expiry date is a shop's date, so it is compared with the shop's
     * today - not UTC's, which is still yesterday for the last hours of a Nairobi evening.
     */
    public java.time.LocalDate today() {
        return java.time.LocalDate.now(shopZone);
    }

    public List<StockItem> belowReorderPoint(UUID branchId) {
        return items.findBelowReorderPoint(branchId);
    }
}
