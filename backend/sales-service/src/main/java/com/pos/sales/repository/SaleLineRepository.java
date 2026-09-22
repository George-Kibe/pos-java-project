package com.pos.sales.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.SaleLine;

public interface SaleLineRepository extends JpaRepository<SaleLine, UUID> {

    List<SaleLine> findBySaleIdOrderByLineNumber(UUID saleId);
}
