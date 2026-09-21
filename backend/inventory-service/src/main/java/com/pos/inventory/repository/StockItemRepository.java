package com.pos.inventory.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.inventory.domain.StockItem;

public interface StockItemRepository extends JpaRepository<StockItem, UUID> {

    Optional<StockItem> findByProductIdAndBranchId(UUID productId, UUID branchId);

    Page<StockItem> findByBranchId(UUID branchId, Pageable pageable);

    List<StockItem> findByProductId(UUID productId);

    @Query(
            """
            SELECT i FROM StockItem i
            WHERE i.branchId = :branchId
              AND i.reorderPoint IS NOT NULL
              AND i.quantityOnHand <= i.reorderPoint
            ORDER BY i.quantityOnHand ASC
            """)
    List<StockItem> findBelowReorderPoint(@Param("branchId") UUID branchId);

    /**
     * Items whose cached quantity disagrees with the sum of their movements.
     *
     * <p>The ledger is the source of truth and the cached figure is derived from it, so this query
     * should always return nothing. It is run as a check rather than trusted to be unnecessary: a
     * disagreement means a code path changed stock without writing a movement, and finding that by
     * report is far better than finding it by a count that will not balance.
     */
    @Query(
            value =
                    """
                    SELECT i.id            AS stockItemId,
                           i.product_id    AS productId,
                           i.branch_id     AS branchId,
                           i.quantity_on_hand AS cachedQuantity,
                           COALESCE(SUM(m.quantity), 0) AS ledgerQuantity
                    FROM stock_items i
                    LEFT JOIN stock_movements m ON m.stock_item_id = i.id
                    GROUP BY i.id, i.product_id, i.branch_id, i.quantity_on_hand
                    HAVING i.quantity_on_hand <> COALESCE(SUM(m.quantity), 0)
                    """,
            nativeQuery = true)
    List<ReconciliationRow> findLedgerDiscrepancies();

    /** One item's cached figure alongside what its movements add up to. */
    interface ReconciliationRow {
        UUID getStockItemId();

        UUID getProductId();

        UUID getBranchId();

        java.math.BigDecimal getCachedQuantity();

        java.math.BigDecimal getLedgerQuantity();
    }
}
