package com.pos.payment.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;

/** One thing that happened to an intent. Append-only: rows are written and never changed. */
@Entity
@Table(name = "payment_events")
@Getter
@NoArgsConstructor
public class PaymentEvent extends BaseEntity {

    @Column(name = "intent_id", updatable = false)
    private UUID intentId;

    @Column(name = "branch_id", updatable = false)
    private UUID branchId;

    @Column(nullable = false, length = 40, updatable = false)
    private String type;

    @Column(length = 1000, updatable = false)
    private String detail;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    public PaymentEvent(UUID intentId, UUID branchId, String type, String detail) {
        this.intentId = intentId;
        this.branchId = branchId;
        this.type = type;
        this.detail =
                detail == null || detail.length() <= 1000 ? detail : detail.substring(0, 1000);
    }
}
