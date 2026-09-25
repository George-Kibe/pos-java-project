package com.pos.inventory.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.inventory.domain.StockMovement;

public interface StockMovementRepository extends JpaRepository<StockMovement, UUID> {

    Page<StockMovement> findByStockItemIdOrderByOccurredAtDesc(UUID stockItemId, Pageable pageable);

    List<StockMovement> findByReferenceTypeAndReferenceId(String referenceType, UUID referenceId);
}
