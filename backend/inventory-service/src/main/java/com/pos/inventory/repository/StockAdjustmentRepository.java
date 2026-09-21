package com.pos.inventory.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.inventory.domain.StockAdjustment;

public interface StockAdjustmentRepository extends JpaRepository<StockAdjustment, UUID> {

    Page<StockAdjustment> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);
}
