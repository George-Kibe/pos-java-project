package com.pos.sales.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.sales.domain.OfflineSyncBatch;

public interface OfflineSyncBatchRepository extends JpaRepository<OfflineSyncBatch, UUID> {

    /** The record of a batch already answered, so the same key replays rather than reprocesses. */
    Optional<OfflineSyncBatch> findByIdempotencyKey(String idempotencyKey);
}
