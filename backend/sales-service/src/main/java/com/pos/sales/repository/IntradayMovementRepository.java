package com.pos.sales.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.sales.domain.cash.IntradayMovement;

public interface IntradayMovementRepository extends JpaRepository<IntradayMovement, UUID> {

    @Query(
            """
            SELECT m.denomination AS denomination, SUM(m.count) AS count
            FROM IntradayMovement m WHERE m.branchId = :branchId
            GROUP BY m.denomination
            """)
    List<DrawerMovementRepository.Holding> holdings(@Param("branchId") UUID branchId);

    Page<IntradayMovement> findByBranchIdOrderByOccurredAtDesc(UUID branchId, Pageable pageable);
}
