package com.pos.customer.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A member's points.
 *
 * <p>The balance here is a cached sum of the ledger, kept because a lane cannot wait for an
 * aggregate over a year of transactions. The ledger remains the truth: every change to this number
 * is written as a row that says why.
 */
@Entity
@Table(name = "loyalty_accounts")
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyAccount extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(name = "tier_id")
    private UUID tierId;

    @Column(name = "points_balance", nullable = false)
    private long pointsBalance;

    @Column(name = "lifetime_points", nullable = false)
    private long lifetimePoints;

    @Column(name = "rolling_spend", nullable = false, precision = 19, scale = 4)
    private BigDecimal rollingSpend = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "last_activity_at")
    private Instant lastActivityAt;

    @Column(name = "last_evaluated_at")
    private Instant lastEvaluatedAt;

    @Column(name = "enrolled_at", nullable = false)
    private Instant enrolledAt = Instant.now();

    public LoyaltyAccount(UUID customerId) {
        this.customerId = customerId;
    }

    /**
     * Applies a movement to the cached balance.
     *
     * @throws IllegalStateException if it would go negative - points are a liability, and a
     *     negative liability is a bug, not a customer who owes the shop points
     */
    public void apply(long points) {
        long balance = pointsBalance + points;
        if (balance < 0) {
            throw new IllegalStateException(
                    "Points balance would go negative: %d%+d".formatted(pointsBalance, points));
        }
        pointsBalance = balance;
        if (points > 0) {
            lifetimePoints += points;
        }
        lastActivityAt = Instant.now();
    }
}
