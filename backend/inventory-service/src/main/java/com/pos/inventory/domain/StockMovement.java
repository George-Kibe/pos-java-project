package com.pos.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One entry in the ledger: what moved, why, and who caused it.
 *
 * <p>Append-only by convention. Nothing in this service updates or deletes one of these; a
 * correction is a new movement in the opposite direction, so what was believed and when survives.
 * That is the whole point - a single mutable quantity answers "how much is there" and nothing else,
 * and "how much is there" is never the question asked when a count disagrees.
 *
 * <p>Quantity is signed, so summing the column for an item reproduces its on-hand figure. The
 * reconciliation check does exactly that.
 */
@Entity
@Table(name = "stock_movements")
@Getter
@Setter
@NoArgsConstructor
public class StockMovement extends BaseEntity {

    @Column(name = "stock_item_id", nullable = false)
    private UUID stockItemId;

    @Column(name = "batch_id")
    private UUID batchId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private MovementType type;

    /** Negative takes stock away, positive puts it on. */
    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(length = 3)
    private String currency;

    @Column(name = "reason_code", length = 50)
    private String reasonCode;

    @Column(name = "reference_type", length = 50)
    private String referenceType;

    @Column(name = "reference_id")
    private UUID referenceId;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();
}
