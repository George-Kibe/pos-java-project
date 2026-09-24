package com.pos.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.sales.domain.Register;

public interface RegisterRepository extends JpaRepository<Register, UUID> {

    List<Register> findByBranchIdOrderByNumberAsc(UUID branchId);

    Optional<Register> findByBranchIdAndNumber(UUID branchId, int number);

    @Query("SELECT COALESCE(MAX(r.number), 0) FROM Register r WHERE r.branchId = :branchId")
    int highestNumber(@Param("branchId") UUID branchId);
}
