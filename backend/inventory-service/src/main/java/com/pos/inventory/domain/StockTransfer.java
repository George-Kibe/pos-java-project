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
 * Stock moving between branches.
 *
 * <p>The in-transit state exists because there is a real period where the goods are on neither
 * shelf. Without it a van full of stock simply disappears for a day, and every report in between is
 * wrong in a way nobody can explain.
 */
@Entity
@Table(name = "stock_transfers")
@Getter
@Setter
@NoArgsConstructor
public class StockTransfer extends BaseEntity {

    public enum Status {
        DRAFT,
        /** Dispatched: deducted from the sender, not yet on the receiver's shelf. */
        IN_TRANSIT,
        RECEIVED,
        CANCELLED
    }

    @Column(nullable = false, length = 50)
    private String reference;

    @Column(name = "from_branch_id", nullable = false)
    private UUID fromBranchId;

    @Column(name = "to_branch_id", nullable = false)
    private UUID toBranchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.DRAFT;

    @Column(columnDefinition = "text")
    private String notes;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "dispatched_by")
    private UUID dispatchedBy;

    @Column(name = "received_at")
    private Instant receivedAt;

    @Column(name = "received_by")
    private UUID receivedBy;

    @OneToMany(
            mappedBy = "transfer",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    private List<StockTransferLine> lines = new ArrayList<>();

    public StockTransfer(String reference, UUID fromBranchId, UUID toBranchId) {
        this.reference = reference;
        this.fromBranchId = fromBranchId;
        this.toBranchId = toBranchId;
    }
}
