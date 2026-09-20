package com.pos.common.persistence;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.EntityListeners;
import jakarta.persistence.Id;
import jakarta.persistence.MappedSuperclass;
import jakarta.persistence.Version;

import org.springframework.data.annotation.CreatedBy;
import org.springframework.data.annotation.CreatedDate;
import org.springframework.data.annotation.LastModifiedBy;
import org.springframework.data.annotation.LastModifiedDate;
import org.springframework.data.jpa.domain.support.AuditingEntityListener;

import com.pos.common.id.UuidV7;

/**
 * Base for every persistent entity: a time-ordered id, who touched it and when, and an optimistic
 * lock.
 *
 * <p>The id is assigned in the constructor rather than by the database. That matters for an offline
 * till, which must be able to create a sale and reference it locally long before the row reaches
 * Postgres, and it lets a whole object graph be built before anything is flushed.
 *
 * <p>{@code version} makes concurrent edits fail loudly with an optimistic lock exception rather
 * than silently overwriting. Two supervisors adjusting the same stock line at once is a real
 * scenario, and last-write-wins would quietly lose one of the adjustments.
 */
@MappedSuperclass
@EntityListeners(AuditingEntityListener.class)
public abstract class BaseEntity {

    @Id
    @Column(nullable = false, updatable = false)
    private UUID id = UuidV7.randomUUID();

    @CreatedDate
    @Column(name = "created_at", nullable = false, updatable = false)
    private Instant createdAt;

    @LastModifiedDate
    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt;

    @CreatedBy
    @Column(name = "created_by", updatable = false)
    private UUID createdBy;

    @LastModifiedBy
    @Column(name = "updated_by")
    private UUID updatedBy;

    @Version
    @Column(nullable = false)
    private long version;

    public UUID getId() {
        return id;
    }

    protected void setId(UUID id) {
        this.id = id;
    }

    public Instant getCreatedAt() {
        return createdAt;
    }

    public Instant getUpdatedAt() {
        return updatedAt;
    }

    public UUID getCreatedBy() {
        return createdBy;
    }

    public UUID getUpdatedBy() {
        return updatedBy;
    }

    public long getVersion() {
        return version;
    }

    /**
     * Identity is the id alone. Using business fields here breaks the moment an entity is edited
     * while sitting in a collection, and using a Hibernate proxy's class breaks lazy loading, so
     * the comparison is deliberately narrow.
     */
    @Override
    public final boolean equals(Object other) {
        if (this == other) {
            return true;
        }
        if (!(other instanceof BaseEntity that)) {
            return false;
        }
        return id != null && id.equals(that.id);
    }

    @Override
    public final int hashCode() {
        // Constant, so an entity keeps its bucket when the id is assigned or the proxy resolves.
        return getClass().getSuperclass().hashCode();
    }

    @Override
    public String toString() {
        return getClass().getSimpleName() + "(" + id + ")";
    }
}
