package com.pos.customer.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;
import com.pos.customer.domain.policy.TierLadder;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A rung of the ladder, as data: a manager changes a threshold without a deployment. */
@Entity
@Table(name = "membership_tiers")
@Getter
@Setter
@NoArgsConstructor
public class MembershipTier extends BaseEntity {

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 80)
    private String name;

    @Column(name = "minimum_rolling_spend", nullable = false, precision = 19, scale = 4)
    private BigDecimal minimumRollingSpend = BigDecimal.ZERO;

    @Column(name = "points_multiplier", nullable = false, precision = 6, scale = 3)
    private BigDecimal pointsMultiplier = BigDecimal.ONE;

    @Column(name = "sort_order", nullable = false)
    private int sortOrder;

    @Column(nullable = false)
    private boolean active = true;

    public TierLadder.Rung toRung() {
        return new TierLadder.Rung(code, minimumRollingSpend, pointsMultiplier);
    }
}
