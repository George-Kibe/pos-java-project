package com.pos.purchasing.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.purchasing.domain.PurchaseOrderLine;

public interface PurchaseOrderLineRepository extends JpaRepository<PurchaseOrderLine, UUID> {

    List<PurchaseOrderLine> findByPurchaseOrderId(UUID purchaseOrderId);
}
