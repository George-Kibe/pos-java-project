package com.pos.customer.domain;

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
 * One movement of points, and - when it adds them - the lot they sit in.
 *
 * <p>{@code points} is signed. A row that adds points also carries {@code pointsRemaining} and
 * {@code expiresAt}, so spending can take the soonest-to-expire first and expiry can write off
 * exactly what lapsed.
 */
@Entity
@Table(name = "loyalty_transactions")
@Getter
@Setter
@NoArgsConstructor
public class LoyaltyTransaction extends BaseEntity {

    @Column(name = "account_id", nullable = false, updatable = false)
    private UUID accountId;

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20, updatable = false)
    private LoyaltyTransactionType type;

    @Column(nullable = false, updatable = false)
    private long points;

    @Column(name = "balance_after", nullable = false)
    private long balanceAfter;

    @Column(name = "points_remaining", nullable = false)
    private long pointsRemaining;

    @Column(name = "expires_at")
    private Instant expiresAt;

    @Column(precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "sale_id")
    private UUID saleId;

    @Column(name = "payment_intent_id")
    private UUID paymentIntentId;

    @Column(name = "return_id")
    private UUID returnId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(length = 500)
    private String reason;

    @Column(name = "actor_id")
    private UUID actorId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public LoyaltyTransaction(LoyaltyAccount account, LoyaltyTransactionType type, long points) {
        this.accountId = account.getId();
        this.customerId = account.getCustomerId();
        this.type = type;
        this.points = points;
        this.pointsRemaining = type.createsLot() && points > 0 ? points : 0;
    }

    /** Takes points out of this lot. */
    public void take(long taken) {
        if (taken > pointsRemaining) {
            throw new IllegalStateException(
                    "Lot %s holds %d points, not %d".formatted(getId(), pointsRemaining, taken));
        }
        pointsRemaining -= taken;
    }

    public boolean isLot() {
        return pointsRemaining > 0;
    }
}
