package com.pos.payment.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.payment.domain.ReconciliationItem;

public interface ReconciliationItemRepository extends JpaRepository<ReconciliationItem, UUID> {

    List<ReconciliationItem> findByRunIdOrderByKindAsc(UUID runId);
}
