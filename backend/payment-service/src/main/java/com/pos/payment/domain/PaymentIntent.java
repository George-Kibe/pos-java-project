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
 * One tender sales asked to be settled.
 *
 * <p>Its id is sales' {@code paymentIntentId}: a redelivered request finds this row rather than
 * creating a second one and charging the customer twice.
 */
@Entity
@Table(name = "payment_intents")
@Getter
@Setter
@NoArgsConstructor
public class PaymentIntent extends BaseEntity {

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "receipt_number", length = 40)
    private String receiptNumber;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "register_id")
    private UUID registerId;

    @Column(name = "cashier_id")
    private UUID cashierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    /** Held only until the push is sent. */
    @Column(name = "phone_number", length = 20)
    private String phoneNumber;

    @Column(name = "phone_masked", length = 20)
    private String phoneMasked;

    @Column(name = "terminal_reference", length = 100)
    private String terminalReference;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private IntentStatus status = IntentStatus.REQUESTED;

    @Column(name = "dispatch_attempts", nullable = false)
    private int dispatchAttempts;

    @Column(name = "dispatched_at")
    private Instant dispatchedAt;

    @Column(name = "amount_authorized", precision = 19, scale = 4)
    private BigDecimal amountAuthorized;

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "approval_code", length = 50)
    private String approvalCode;

    @Column(name = "failure_code", length = 50)
    private String failureCode;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(nullable = false)
    private boolean late;

    @Column(name = "correlation_id", length = 64)
    private String correlationId;

    /** The payment-requested event this intent answers. */
    @Column(name = "causation_id", length = 64)
    private String causationId;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    public PaymentIntent(
            UUID id, UUID saleId, UUID branchId, PaymentMethod method, BigDecimal amount) {
        setId(id);
        this.saleId = saleId;
        this.branchId = branchId;
        this.method = method;
        this.amount = amount;
    }

    public void authorize(BigDecimal authorized, String reference, String code) {
        // Money arriving after a failure was declared is still money: recorded, and marked late.
        this.late = status == IntentStatus.FAILED;
        this.status = IntentStatus.AUTHORIZED;
        this.amountAuthorized = authorized;
        this.providerReference = reference;
        this.approvalCode = code;
        this.failureCode = null;
        this.failureMessage = null;
        this.settledAt = Instant.now();
    }

    public void fail(String code, String message) {
        this.status = IntentStatus.FAILED;
        this.failureCode = code;
        this.failureMessage =
                message == null || message.length() <= 500 ? message : message.substring(0, 500);
        this.settledAt = Instant.now();
    }

    /** The full number is not kept a moment longer than the push needed it. */
    public void forgetPhoneNumber() {
        this.phoneNumber = null;
    }
}
