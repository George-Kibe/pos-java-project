package com.pos.payment.repository;

import java.util.Optional;
import java.util.UUID;

import jakarta.persistence.LockModeType;

import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Lock;
import org.springframework.data.jpa.repository.Query;

import com.pos.payment.domain.MpesaTransaction;

public interface MpesaTransactionRepository extends JpaRepository<MpesaTransaction, UUID> {

    /**
     * Locked, because a callback and the status sweep can reach the same transaction at the same
     * moment, and only one of them may settle it.
     */
    @Lock(LockModeType.PESSIMISTIC_WRITE)
    @Query("SELECT t FROM MpesaTransaction t WHERE t.checkoutRequestId = :id")
    Optional<MpesaTransaction> lockByCheckoutRequestId(String id);

    Optional<MpesaTransaction> findByCheckoutRequestId(String id);

    Optional<MpesaTransaction> findFirstByIntentIdOrderByCreatedAtDesc(UUID intentId);
}
