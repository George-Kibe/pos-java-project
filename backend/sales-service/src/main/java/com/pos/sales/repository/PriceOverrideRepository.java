package com.pos.sales.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.PriceOverride;

public interface PriceOverrideRepository extends JpaRepository<PriceOverride, UUID> {

    Page<PriceOverride> findByBranchIdOrderByOccurredAtDesc(UUID branchId, Pageable pageable);
}
