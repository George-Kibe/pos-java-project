package com.pos.sales.repository;

import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.Receipt;

public interface ReceiptRepository extends JpaRepository<Receipt, UUID> {

    Optional<Receipt> findByReceiptNumberAndType(String receiptNumber, Receipt.Type type);

    List<Receipt> findBySaleId(UUID saleId);
}
