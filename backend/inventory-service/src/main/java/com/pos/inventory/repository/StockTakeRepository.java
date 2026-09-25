package com.pos.inventory.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.inventory.domain.StockTake;

public interface StockTakeRepository extends JpaRepository<StockTake, UUID> {

    /** With each line's stock item: the count sheet names the product it is counting. */
    @EntityGraph(attributePaths = {"lines", "lines.stockItem"})
    Optional<StockTake> findWithLinesById(UUID id);

    Optional<StockTake> findByReference(String reference);

    boolean existsByReference(String reference);

    Page<StockTake> findByBranchIdOrderByCreatedAtDesc(UUID branchId, Pageable pageable);
}
