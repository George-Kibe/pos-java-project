package com.pos.sales.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.ReceiptSettings;

public interface ReceiptSettingsRepository extends JpaRepository<ReceiptSettings, UUID> {

    Optional<ReceiptSettings> findByBranchId(UUID branchId);
}
