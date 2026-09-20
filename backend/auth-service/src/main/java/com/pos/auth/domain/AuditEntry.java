package com.pos.auth.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pos.common.id.UuidV7;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One privileged action, recorded for the audit trail.
 *
 * <p>Append-only by convention: nothing in this service ever updates or deletes a row here. An
 * audit log that can be rewritten is not an audit log.
 */
@Entity
@Table(name = "audit_log")
@Getter
@Setter
@NoArgsConstructor
public class AuditEntry {

    @Id private UUID id = UuidV7.randomUUID();

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "actor_email", length = 255)
    private String actorEmail;

    @Column(nullable = false, length = 100)
    private String action;

    @Column(name = "resource_type", length = 100)
    private String resourceType;

    @Column(name = "resource_id", length = 100)
    private String resourceId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb")
    private String details;

    @Column(name = "ip_address", length = 45)
    private String ipAddress;

    @Column(name = "created_at", nullable = false)
    private Instant createdAt = Instant.now();

    @Override
    public boolean equals(Object other) {
        return other instanceof AuditEntry e && id != null && id.equals(e.id);
    }

    @Override
    public int hashCode() {
        return AuditEntry.class.hashCode();
    }
}
