package com.pos.payment.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.payment.domain.ReconciliationRun;

public interface ReconciliationRunRepository extends JpaRepository<ReconciliationRun, UUID> {

    Page<ReconciliationRun> findAllByOrderByStatementDateDescCreatedAtDesc(Pageable pageable);
}
