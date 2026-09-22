package com.pos.payment.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.pos.payment.domain.Refund;
import com.pos.payment.domain.RefundStatus;

public interface RefundRepository extends JpaRepository<Refund, UUID> {

    List<Refund> findByReturnId(UUID returnId);

    Page<Refund> findByBranchIdOrderByRequestedAtDesc(UUID branchId, Pageable pageable);

    Page<Refund> findByBranchIdAndStatusOrderByRequestedAtDesc(
            UUID branchId, RefundStatus status, Pageable pageable);

    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT r FROM Refund r WHERE r.originatorConversationId = :id")
    Optional<Refund> lockByOriginatorConversationId(String id);
}
