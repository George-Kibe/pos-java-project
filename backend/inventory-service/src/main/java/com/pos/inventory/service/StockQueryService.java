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

import lombok.RequiredArgsConstructor;

/**
 * The read side of stock.
 *
 * <p>Separate from {@link StockService} so a controller never reaches into a repository: reads run
 * inside a read-only transaction like every other database access, and the mapping to DTOs stays
 * the only thing the controller does with the result.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockQueryService {

    private final StockItemRepository items;
    private final StockBatchRepository batches;
    private final StockMovementRepository movements;
    private final StockService stock;

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

    public List<StockItem> belowReorderPoint(UUID branchId) {
        return items.findBelowReorderPoint(branchId);
    }
}
