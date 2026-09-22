package com.pos.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Cash in or out of a drawer, other than a sale or a refund.
 *
 * <p>Its own rows so the close-of-shift arithmetic can be shown rather than asserted. A drawer that
 * is 20,000 short because nobody recorded the drop is an accusation waiting to be made.
 */
@Entity
@Table(name = "cash_movements")
@Getter
@Setter
@NoArgsConstructor
public class CashMovement extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "till_session_id", nullable = false)
    private TillSession tillSession;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CashMovementType type;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(length = 500)
    private String reason;

    @Column(length = 100)
    private String reference;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public CashMovement(
            TillSession tillSession, CashMovementType type, BigDecimal amount, String reason) {
        this.tillSession = tillSession;
        this.branchId = tillSession.getBranchId();
        this.type = type;
        this.amount = amount;
        this.reason = reason;
        this.currency = tillSession.getCurrency();
    }
}
