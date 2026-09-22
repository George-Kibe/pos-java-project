package com.pos.customer.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;

/**
 * Points taken out of one lot by one spend.
 *
 * <p>What lets a reversal put them back where they were, with the expiry they had.
 */
@Entity
@Table(name = "loyalty_lot_takes")
@Getter
@NoArgsConstructor
public class LoyaltyLotTake extends BaseEntity {

    @Column(name = "transaction_id", nullable = false, updatable = false)
    private UUID transactionId;

    @Column(name = "lot_id", nullable = false, updatable = false)
    private UUID lotId;

    @Column(nullable = false, updatable = false)
    private long points;

    public LoyaltyLotTake(UUID transactionId, UUID lotId, long points) {
        this.transactionId = transactionId;
        this.lotId = lotId;
        this.points = points;
    }
}
