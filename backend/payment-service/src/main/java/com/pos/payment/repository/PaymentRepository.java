package com.pos.payment.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.events.payments.PaymentMethod;
import com.pos.payment.domain.Payment;

public interface PaymentRepository extends JpaRepository<Payment, UUID> {

    Optional<Payment> findByIntentId(UUID intentId);

    List<Payment> findBySaleIdAndMethod(UUID saleId, PaymentMethod method);

    List<Payment> findByMethodAndReceivedAtBetween(
            PaymentMethod method, Instant fromInclusive, Instant toExclusive);

    List<Payment> findByMpesaReceiptNumberIn(List<String> receipts);
}
