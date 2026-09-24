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
