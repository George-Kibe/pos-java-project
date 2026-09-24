package com.pos.sales.domain.cash;

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

/** Notes or coins into (positive) or out of (negative) a drawer. */
@Entity
@Table(name = "drawer_movements")
@Getter
@Setter
@NoArgsConstructor
public class DrawerMovement extends BaseEntity {

    public enum Kind {
        OPENING_FLOAT,
        FLOAT_IN,
        REPLENISH,
        SALE_IN,
        SALE_CHANGE,
        VOID_OUT,
        REFUND_OUT,
        DEPOSIT,
        /** Notes taken in exchange for others of the same total: breaking a 1000, say. */
        EXCHANGE_IN,
        EXCHANGE_OUT
    }

    @Column(name = "till_session_id", nullable = false)
    private UUID tillSessionId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "source_id")
    private UUID sourceId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal denomination;

    @Column(nullable = false)
    private int count;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public DrawerMovement(
            UUID tillSessionId,
            UUID branchId,
            Kind kind,
            UUID sourceId,
            BigDecimal denomination,
            int count) {
        this.tillSessionId = tillSessionId;
        this.branchId = branchId;
        this.kind = kind;
        this.sourceId = sourceId;
        this.denomination = denomination;
        this.count = count;
    }
}
