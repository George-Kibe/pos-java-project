package com.pos.inventory.repository;

import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.inventory.domain.StockBatch;

public interface StockBatchRepository extends JpaRepository<StockBatch, UUID> {

    /**
     * Sellable batches for an item, in FEFO order.
     *
     * <p>Ordered here as well as in the allocator: the database can use the partial index, and the
     * allocator sorts again so it is correct when called with any list at all.
     */
    @Query(
            """
            SELECT b FROM StockBatch b
            WHERE b.stockItem.id = :stockItemId AND b.status = com.pos.inventory.domain.BatchStatus.ACTIVE
              AND b.quantity > 0
            ORDER BY b.expiryDate ASC NULLS LAST, b.receivedAt ASC, b.batchNumber ASC
            """)
    List<StockBatch> findSellable(@Param("stockItemId") UUID stockItemId);

    Optional<StockBatch> findByStockItemIdAndBatchNumber(UUID stockItemId, String batchNumber);

    /** Active batches expiring on or before a date, for the near-expiry sweep. */
    @Query(
            """
            SELECT b FROM StockBatch b
            WHERE b.status = com.pos.inventory.domain.BatchStatus.ACTIVE
              AND b.quantity > 0
              AND b.expiryDate IS NOT NULL
              AND b.expiryDate <= :cutoff
            ORDER BY b.expiryDate ASC
            """)
    List<StockBatch> findExpiringOnOrBefore(@Param("cutoff") LocalDate cutoff);

    List<StockBatch> findByStockItemId(UUID stockItemId);
}
