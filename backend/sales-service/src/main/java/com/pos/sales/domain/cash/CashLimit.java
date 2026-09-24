package com.pos.sales.domain.cash;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How much cash a till may hold: at a branch by default (no user), or for one person there. Past
 * the limit the lane asks for a deposit; past the ceiling it takes no more cash.
 */
@Entity
@Table(name = "cash_limits")
@Getter
@Setter
@NoArgsConstructor
public class CashLimit extends BaseEntity {

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "user_id")
    private UUID userId;

    @Column(name = "limit_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal limitAmount;

    @Column(name = "ceiling_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal ceilingAmount;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    public CashLimit(UUID branchId, UUID userId) {
        this.branchId = branchId;
        this.userId = userId;
    }
}
