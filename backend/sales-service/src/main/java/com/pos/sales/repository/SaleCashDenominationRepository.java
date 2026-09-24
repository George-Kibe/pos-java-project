package com.pos.sales.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.cash.SaleCashDenomination;

public interface SaleCashDenominationRepository extends JpaRepository<SaleCashDenomination, UUID> {

    List<SaleCashDenomination> findBySaleId(UUID saleId);
}
