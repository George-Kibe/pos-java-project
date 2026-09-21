package com.pos.inventory.repository;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.inventory.domain.StockMovement;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Page<StockMovement> findByStockItemIdOrderByOccurredAtDesc(UUID stockItemId, Pageable pageable);

    List<StockMovement> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);

    /** What the ledger says this item holds. The cached figure should equal this. */
    @Query(
            "SELECT COALESCE(SUM(m.quantity), 0) FROM StockMovement m WHERE m.stockItemId = :stockItemId")
    BigDecimal sumQuantityFor(@Param("stockItemId") UUID stockItemId);
}
