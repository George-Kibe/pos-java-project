package com.pos.sales.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.TillSessionStatus;

public interface TillSessionRepository extends JpaRepository<TillSession, UUID> {

    /** The session a register is currently on, if any. */
    Optional<TillSession> findByRegisterIdAndStatusNot(UUID registerId, TillSessionStatus status);

    Page<TillSession> findByBranchIdOrderByOpenedAtDesc(UUID branchId, Pageable pageable);

    Optional<TillSession> findByCashierIdAndStatus(UUID cashierId, TillSessionStatus status);

    /** A branch's shifts still holding a drawer: open, or closing. */
    java.util.List<TillSession> findByBranchIdAndStatusNotOrderByOpenedAt(
            UUID branchId, TillSessionStatus status);

    @org.springframework.data.jpa.repository.Query(
            "SELECT DISTINCT s.branchId FROM TillSession s WHERE s.status <> :closed")
    java.util.List<UUID> branchesWithShiftsNot(
            @org.springframework.data.repository.query.Param("closed") TillSessionStatus closed);
}
