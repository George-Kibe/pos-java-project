package com.pos.purchasing.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.purchasing.domain.ExpenseSettings;

public interface ExpenseSettingsRepository extends JpaRepository<ExpenseSettings, UUID> {

    Optional<ExpenseSettings> findFirstByOrderByCreatedAtAsc();
}
