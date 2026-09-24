package com.pos.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.cash.CashLimit;

public interface CashLimitRepository extends JpaRepository<CashLimit, UUID> {

    Optional<CashLimit> findByBranchIdAndUserIdIsNull(UUID branchId);

    Optional<CashLimit> findByBranchIdAndUserId(UUID branchId, UUID userId);

    List<CashLimit> findByBranchIdOrderByUserIdAsc(UUID branchId);
}
