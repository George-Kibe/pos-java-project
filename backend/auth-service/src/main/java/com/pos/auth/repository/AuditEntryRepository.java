package com.pos.auth.repository;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.auth.domain.AuditEntry;

/**
 * The audit trail, newest first. One derived query per filter combination rather than a single
 * {@code :x IS NULL OR ...} query: PostgreSQL rejects an untyped null parameter, which is what
 * broke user search.
 */
public interface AuditEntryRepository
        extends JpaRepository<AuditEntry, UUID>,
                org.springframework.data.jpa.repository.JpaSpecificationExecutor<AuditEntry> {

    Page<AuditEntry> findAllByOrderByCreatedAtDesc(Pageable pageable);

    Page<AuditEntry> findByActionOrderByCreatedAtDesc(String action, Pageable pageable);

    Page<AuditEntry> findByActorIdOrderByCreatedAtDesc(UUID actorId, Pageable pageable);

    Page<AuditEntry> findByActionAndActorIdOrderByCreatedAtDesc(
            String action, UUID actorId, Pageable pageable);
}
