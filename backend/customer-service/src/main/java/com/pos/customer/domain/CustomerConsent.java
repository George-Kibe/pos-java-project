package com.pos.customer.domain;

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

/**
 * One thing a customer agreed to, or withdrew. Append-only.
 *
 * <p>History rather than a flag: "we had consent at the time" is the only defensible answer to a
 * complaint about a message sent last year, and a flag cannot say it.
 */
@Entity
@Table(name = "customer_consents")
@Getter
@NoArgsConstructor
public class CustomerConsent extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30, updatable = false)
    private ConsentChannel channel;

    @Column(nullable = false, updatable = false)
    private boolean granted;

    @Column(nullable = false, length = 30, updatable = false)
    private String source = "TILL";

    @Column(length = 500, updatable = false)
    private String note;

    @Column(name = "occurred_at", nullable = false, updatable = false)
    private Instant occurredAt = Instant.now();

    public CustomerConsent(
            UUID customerId, ConsentChannel channel, boolean granted, String source, String note) {
        this.customerId = customerId;
        this.channel = channel;
        this.granted = granted;
        this.source = source == null || source.isBlank() ? "TILL" : source;
        this.note = note;
    }
}
