package com.pos.auth.repository;

import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.auth.domain.AuditEntry;

/**
 * Audit entries are written here and read by the audit viewer, which arrives with the back office
 * in Phase 15. The filtered query lands then, with its own tests - rather than shipping now as
 * untested code carrying the same null-parameter hazard that broke user search: PostgreSQL rejects
 * an untyped null string parameter inside a function call.
 */
public interface AuditEntryRepository extends JpaRepository<AuditEntry, UUID> {}
