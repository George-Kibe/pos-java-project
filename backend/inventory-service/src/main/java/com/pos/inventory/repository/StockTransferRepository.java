package com.pos.inventory.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.inventory.domain.StockTransfer;

public interface StockTransferRepository extends JpaRepository<StockTransfer, UUID> {

    Optional<StockTransfer> findByReference(String reference);

    boolean existsByReference(String reference);

    /** Transfers involving a branch, in either direction. */
    @Query(
            """
            SELECT t FROM StockTransfer t
            WHERE t.fromBranchId = :branchId OR t.toBranchId = :branchId
            ORDER BY t.createdAt DESC
            """)
    Page<StockTransfer> findInvolvingBranch(@Param("branchId") UUID branchId, Pageable pageable);
}
