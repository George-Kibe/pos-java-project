package com.pos.sales.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.sales.domain.ReceiptSequence;

public interface ReceiptSequenceRepository extends JpaRepository<ReceiptSequence, UUID> {

    /**
     * The branch's counter, locked for update.
     *
     * <p>Pessimistic on purpose. Two lanes paying at the same instant must not both read 4,411 and
     * both write 4,412 - an optimistic retry would work but would fail a customer's checkout to do
     * it, and the lock is held for microseconds against only the same branch.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT s FROM ReceiptSequence s WHERE s.branchId = :branchId")
    Optional<ReceiptSequence> lockForBranch(@Param("branchId") UUID branchId);
}
