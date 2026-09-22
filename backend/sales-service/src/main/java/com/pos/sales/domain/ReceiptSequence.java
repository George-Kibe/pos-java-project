package com.pos.sales.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Id;
import jakarta.persistence.Table;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The next receipt number for a branch.
 *
 * <p>Not a Postgres sequence, deliberately. A sequence is designed to leave gaps when a transaction
 * rolls back, and "receipt 4,412 does not exist" is exactly what a tax audit asks about. A counter
 * row taken with a pessimistic lock rolls back with the sale that claimed it, so the numbers stay
 * contiguous.
 *
 * <p>Not a {@link com.pos.common.persistence.BaseEntity}: this is a counter, not a record of
 * something that happened, and it has no id of its own beyond the branch it belongs to.
 */
@Entity
@Table(name = "receipt_sequences")
@Getter
@Setter
@NoArgsConstructor
public class ReceiptSequence {

    @Id
    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(nullable = false, length = 10)
    private String prefix = "R";

    @Column(name = "next_number", nullable = false)
    private long nextNumber = 1;

    @Column(name = "updated_at", nullable = false)
    private Instant updatedAt = Instant.now();

    public ReceiptSequence(UUID branchId, String prefix) {
        this.branchId = branchId;
        this.prefix = prefix == null ? "R" : prefix;
    }

    /** Takes the next number and advances the counter. Call only under a lock. */
    public String take() {
        String number = "%s-%06d".formatted(prefix, nextNumber);
        this.nextNumber = nextNumber + 1;
        this.updatedAt = Instant.now();
        return number;
    }
}
