package com.pos.inventory.domain;

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
 * Stock held for an open cart.
 *
 * <p>Writes no movement: the goods are still on the shelf, they are simply not promised to anyone
 * else. Reservations expire because a cashier who walks away from a suspended cart must not hold
 * the last of something out of sale for the rest of the day.
 */
@Entity
@Table(name = "stock_reservations")
@Getter
@Setter
@NoArgsConstructor
public class StockReservation extends BaseEntity {

    public enum Status {
        HELD,
        /** The sale completed and the hold became a deduction. */
        CONSUMED,
        RELEASED,
        EXPIRED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "reference_type", nullable = false, length = 50)
    private String referenceType;

    @Column(name = "reference_id", nullable = false)
    private UUID referenceId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.HELD;

    @Column(name = "expires_at", nullable = false)
    private Instant expiresAt;

    @Column(name = "released_at")
    private Instant releasedAt;

    public StockReservation(
            StockItem stockItem,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId,
            Instant expiresAt) {
        this.stockItem = stockItem;
        this.quantity = quantity;
        this.referenceType = referenceType;
        this.referenceId = referenceId;
        this.expiresAt = expiresAt;
    }

    public boolean isHeld() {
        return status == Status.HELD;
    }
}
