package com.pos.catalog.repository;

import java.util.List;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.catalog.domain.barcode.ScaleBarcodeRule;

public interface ScaleBarcodeRuleRepository extends JpaRepository<ScaleBarcodeRule, UUID> {

    List<ScaleBarcodeRule> findByActiveTrueOrderByPrefixAsc();
}
