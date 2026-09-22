package com.pos.payment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.payment.domain.PaymentEvent;

public interface PaymentEventRepository extends JpaRepository<PaymentEvent, UUID> {

    List<PaymentEvent> findByIntentIdOrderByOccurredAt(UUID intentId);
}
