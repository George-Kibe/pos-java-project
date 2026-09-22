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

/**
 * Money received.
 *
 * <p>{@code amount} is what settles the sale; {@code amountCharged} is what the provider took. They
 * differ only by M-Pesa's whole-shilling rounding, kept as {@code roundingDifference}.
 */
@Entity
@Table(name = "payments")
@Getter
@Setter
@NoArgsConstructor
public class Payment extends BaseEntity {

    @Column(name = "intent_id", nullable = false, updatable = false)
    private UUID intentId;

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "amount_charged", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountCharged;

    @Column(name = "rounding_difference", nullable = false, precision = 19, scale = 4)
    private BigDecimal roundingDifference = BigDecimal.ZERO;

    @Column(name = "amount_refunded", nullable = false, precision = 19, scale = 4)
    private BigDecimal amountRefunded = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "approval_code", length = 50)
    private String approvalCode;

    @Column(name = "mpesa_receipt_number", length = 30)
    private String mpesaReceiptNumber;

    @Column(nullable = false)
    private boolean late;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    public static Payment of(PaymentIntent intent, BigDecimal settles, BigDecimal charged) {
        Payment payment = new Payment();
        payment.intentId = intent.getId();
        payment.saleId = intent.getSaleId();
        payment.branchId = intent.getBranchId();
        payment.method = intent.getMethod();
        payment.currency = intent.getCurrency();
        payment.amount = settles;
        payment.amountCharged = charged;
        payment.roundingDifference = charged.subtract(settles);
        payment.providerReference = intent.getProviderReference();
        payment.approvalCode = intent.getApprovalCode();
        payment.late = intent.isLate();
        return payment;
    }

    public BigDecimal refundable() {
        return amount.subtract(amountRefunded);
    }
}
