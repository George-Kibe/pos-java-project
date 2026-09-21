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
 * A deliberate correction to stock, always with a reason.
 *
 * <p>Drafted, then posted. Posting is what writes movements, so an adjustment can be prepared and
 * reviewed before it changes anything - which matters because an adjustment is the one way stock
 * can change without a sale, a delivery or a count behind it.
 */
@Entity
@Table(name = "stock_adjustments")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustment extends BaseEntity {

    public enum Status {
        DRAFT,
        POSTED,
        CANCELLED
    }

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 50)
    private AdjustmentReason reasonCode;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    @OneToMany(
            mappedBy = "adjustment",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    private List<StockAdjustmentLine> lines = new ArrayList<>();

    public StockAdjustment(UUID branchId, AdjustmentReason reasonCode, String notes) {
        this.branchId = branchId;
        this.reasonCode = reasonCode;
        this.notes = notes;
    }

    public void addLine(
            StockItem stockItem, StockBatch batch, java.math.BigDecimal delta, String note) {
        lines.add(new StockAdjustmentLine(this, stockItem, batch, delta, note));
    }
}
