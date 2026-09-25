package com.pos.auth.service;

import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.auth.domain.AuditEntry;
import com.pos.auth.repository.AuditEntryRepository;

import lombok.RequiredArgsConstructor;

/** Reads the audit trail: who signed in, who changed a role, who approved at a lane. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class AuditQueryService {

    private final AuditEntryRepository entries;

    /**
     * The trail filtered by any of action, who did it, where, and when. Built from criteria so a
     * filter that is absent is left out of the query entirely - never sent as a null parameter,
     * which PostgreSQL rejects inside a comparison.
     */
    public Page<AuditEntry> search(
            String action,
            UUID actorId,
            UUID branchId,
            java.time.Instant from,
            java.time.Instant to,
            Pageable pageable) {
        org.springframework.data.jpa.domain.Specification<AuditEntry> where =
                (root, query, cb) -> cb.conjunction();
        if (action != null && !action.isBlank()) {
            where = where.and((root, query, cb) -> cb.equal(root.get("action"), action.trim()));
        }
        if (actorId != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("actorId"), actorId));
        }
        if (branchId != null) {
            where = where.and((root, query, cb) -> cb.equal(root.get("branchId"), branchId));
        }
        if (from != null) {
            where =
                    where.and(
                            (root, query, cb) ->
                                    cb.greaterThanOrEqualTo(
                                            root.<java.time.Instant>get("createdAt"), from));
        }
        if (to != null) {
            where =
                    where.and(
                            (root, query, cb) ->
                                    cb.lessThan(root.<java.time.Instant>get("createdAt"), to));
        }
        Pageable newestFirst =
                org.springframework.data.domain.PageRequest.of(
                        pageable.getPageNumber(),
                        pageable.getPageSize(),
                        org.springframework.data.domain.Sort.by(
                                org.springframework.data.domain.Sort.Direction.DESC, "createdAt"));
        return entries.findAll(where, newestFirst);
    }

    public Page<AuditEntry> search(String action, UUID actorId, Pageable pageable) {
        boolean byAction = action != null && !action.isBlank();
        if (byAction && actorId != null) {
            return entries.findByActionAndActorIdOrderByCreatedAtDesc(action, actorId, pageable);
        }
        if (byAction) {
            return entries.findByActionOrderByCreatedAtDesc(action, pageable);
        }
        if (actorId != null) {
            return entries.findByActorIdOrderByCreatedAtDesc(actorId, pageable);
        }
        return entries.findAllByOrderByCreatedAtDesc(pageable);
    }
}
