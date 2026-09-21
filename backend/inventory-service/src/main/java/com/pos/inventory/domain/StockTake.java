package com.pos.inventory.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A physical count.
 *
 * <p>Snapshot, count, review, post. The snapshot is taken when counting begins and kept, so a
 * variance is measured against what was believed at that moment rather than against a figure that
 * moved while people were walking the aisles.
 */
@Entity
@Table(name = "stock_takes")
@Getter
@Setter
@NoArgsConstructor
public class StockTake extends BaseEntity {

    public enum Status {
        OPEN,
        COUNTING,
        /** Counted; variances visible and awaiting approval. */
        REVIEW,
        POSTED,
        CANCELLED
    }

    @Column(nullable = false, length = 50)
    private String reference;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "snapshot_at")
    private Instant snapshotAt;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    @OneToMany(
            mappedBy = "stockTake",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    private List<StockTakeLine> lines = new ArrayList<>();

    public StockTake(String reference, UUID branchId) {
        this.reference = reference;
        this.branchId = branchId;
    }
}
