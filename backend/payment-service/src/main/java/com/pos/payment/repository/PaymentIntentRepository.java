package com.pos.payment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.payment.domain.PaymentIntent;

public interface PaymentIntentRepository extends JpaRepository<PaymentIntent, UUID> {

    List<PaymentIntent> findBySaleIdOrderByRequestedAt(UUID saleId);

    Page<PaymentIntent> findByBranchIdOrderByRequestedAtDesc(UUID branchId, Pageable pageable);
}
