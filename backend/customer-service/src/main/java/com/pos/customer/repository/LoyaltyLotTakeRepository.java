package com.pos.customer.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.customer.domain.LoyaltyLotTake;

public interface LoyaltyLotTakeRepository extends JpaRepository<LoyaltyLotTake, UUID> {

    List<LoyaltyLotTake> findByTransactionId(UUID transactionId);
}
