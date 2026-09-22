package com.pos.payment.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;
import com.pos.events.payments.PaymentMethod;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Money going back through the provider it came in by. One per (return, payment). */
@Entity
@Table(name = "refunds")
@Getter
@Setter
@NoArgsConstructor
public class Refund extends BaseEntity {

    @Column(name = "payment_id", nullable = false, updatable = false)
    private UUID paymentId;

    @Column(name = "intent_id", nullable = false, updatable = false)
    private UUID intentId;

    @Column(name = "sale_id", nullable = false, updatable = false)
    private UUID saleId;

    @Column(name = "return_id", nullable = false, updatable = false)
    private UUID returnId;

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private RefundStatus status;

    @Column(name = "action_reason", length = 50)
    private String actionReason;

    @Column(length = 500)
    private String detail;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "originator_conversation_id", length = 100)
    private String originatorConversationId;

    @Column(name = "conversation_id", length = 100)
    private String conversationId;

    @Column(name = "settled_via", length = 30)
    private String settledVia;

    @Column(name = "settled_by")
    private UUID settledBy;

    @Column(name = "settlement_note", length = 500)
    private String settlementNote;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    public Refund(Payment payment, UUID returnId, BigDecimal amount, RefundStatus status) {
        this.paymentId = payment.getId();
        this.intentId = payment.getIntentId();
        this.saleId = payment.getSaleId();
        this.branchId = payment.getBranchId();
        this.method = payment.getMethod();
        this.currency = payment.getCurrency();
        this.returnId = returnId;
        this.amount = amount;
        this.status = status;
    }

    public void requireAction(String reason, String why) {
        this.status = RefundStatus.REQUIRES_ACTION;
        this.actionReason = reason;
        this.detail = why == null || why.length() <= 500 ? why : why.substring(0, 500);
    }

    public void complete(String reference, String via) {
        this.status = RefundStatus.COMPLETED;
        this.providerReference = reference;
        this.settledVia = via;
        this.completedAt = Instant.now();
    }
}
