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

/** Notes or coins into or out of the branch's intraday cash. */
@Entity
@Table(name = "intraday_movements")
@Getter
@Setter
@NoArgsConstructor
public class IntradayMovement extends BaseEntity {

    public enum Kind {
        /** Brought in: from the bank, or the opening balance. */
        TOP_UP,
        /** Taken to the bank. */
        BANKED,
        /** A till's deposit. */
        FROM_TILL,
        /** A till's replenishment. */
        TO_TILL
    }

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Kind kind;

    @Column(name = "till_session_id")
    private UUID tillSessionId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal denomination;

    @Column(nullable = false)
    private int count;

    @Column(length = 500)
    private String reason;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public IntradayMovement(
            UUID branchId,
            Kind kind,
            UUID tillSessionId,
            BigDecimal denomination,
            int count,
            String reason) {
        this.branchId = branchId;
        this.kind = kind;
        this.tillSessionId = tillSessionId;
        this.denomination = denomination;
        this.count = count;
        this.reason = reason;
    }
}
