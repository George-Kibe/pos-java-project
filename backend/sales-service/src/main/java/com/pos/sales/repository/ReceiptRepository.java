package com.pos.sales.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.Receipt;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    List<Receipt> findBySaleId(UUID saleId);
}
