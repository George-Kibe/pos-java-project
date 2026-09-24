package com.pos.auth.api;

import java.time.Instant;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.auth.domain.AuditEntry;
import com.pos.auth.service.AuditQueryService;
import com.pos.common.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** The audit trail of accounts, roles, branches, sign-ins and supervisor approvals. */
@RestController
@RequestMapping("/api/v1/audit")
@RequiredArgsConstructor
@Tag(name = "Audit")
public class AuditController {

    private final AuditQueryService audit;

    public record AuditEntryResponse(
            UUID id,
            Instant at,
            UUID actorId,
            String actorEmail,
            String action,
            String resourceType,
            String resourceId,
            UUID branchId,
            String correlationId,
            String details) {

        static AuditEntryResponse from(AuditEntry entry) {
            return new AuditEntryResponse(
                    entry.getId(),
                    entry.getCreatedAt(),
                    entry.getActorId(),
                    entry.getActorEmail(),
                    entry.getAction(),
                    entry.getResourceType(),
                    entry.getResourceId(),
                    entry.getBranchId(),
                    entry.getCorrelationId(),
                    entry.getDetails());
        }
    }

    @GetMapping
    @PreAuthorize("hasAuthority('audit:view')")
    @Operation(summary = "The audit trail, newest first; filter by action and by who did it")
    public PageResponse<AuditEntryResponse> search(
            @RequestParam(required = false) String action,
            @RequestParam(required = false) UUID actorId,
            @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(audit.search(action, actorId, pageable), AuditEntryResponse::from);
    }
}
