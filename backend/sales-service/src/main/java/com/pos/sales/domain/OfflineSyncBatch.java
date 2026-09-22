package com.pos.sales.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A batch of sales a terminal queued while offline, and the answer it was given.
 *
 * <p>Keyed on the terminal's {@code Idempotency-Key} and storing the response verbatim, so
 * resubmitting the same batch replays the first answer instead of processing it twice. Kept rather
 * than discarded because "the till says it synced and head office has no record" is the worst
 * failure this system can have, and the only defence is a record of every batch that arrived.
 */
@Entity
@Table(name = "offline_sync_batches")
@Getter
@Setter
@NoArgsConstructor
public class OfflineSyncBatch extends BaseEntity {

    @Column(name = "idempotency_key", nullable = false, length = 120)
    private String idempotencyKey;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "register_id")
    private UUID registerId;

    @Column(name = "cashier_id")
    private UUID cashierId;

    @Column(name = "submitted_at", nullable = false)
    private Instant submittedAt = Instant.now();

    @Column(name = "sale_count", nullable = false)
    private int saleCount;

    @Column(name = "accepted_count", nullable = false)
    private int acceptedCount;

    @Column(name = "duplicate_count", nullable = false)
    private int duplicateCount;

    @Column(name = "rejected_count", nullable = false)
    private int rejectedCount;

    @Column(name = "variance_count", nullable = false)
    private int varianceCount;

    /** The per-sale answer, replayed verbatim if the same key arrives again. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(columnDefinition = "jsonb", nullable = false)
    private String result;

    public OfflineSyncBatch(String idempotencyKey, UUID branchId) {
        this.idempotencyKey = idempotencyKey;
        this.branchId = branchId;
    }
}
