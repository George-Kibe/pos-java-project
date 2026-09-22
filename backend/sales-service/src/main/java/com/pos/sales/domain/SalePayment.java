package com.pos.sales.domain;

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

import com.pos.common.id.UuidV7;
import com.pos.common.persistence.BaseEntity;
import com.pos.events.payments.PaymentMethod;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One tender against a sale.
 *
 * <p>A basket split across cash and M-Pesa is two rows, each authorised on its own, because they
 * fail independently: the cash is in the drawer whatever the phone does.
 *
 * <p>Card rows carry a terminal reference and an approval code and nothing else. The platform never
 * stores card data, and a phone number is masked before it reaches this row.
 */
@Entity
@Table(name = "sale_payments")
@Getter
@Setter
@NoArgsConstructor
public class SalePayment extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    /** Assigned here, so a duplicated request from a retrying till is recognisable. */
    @Column(name = "payment_intent_id", nullable = false)
    private UUID paymentIntentId = UuidV7.randomUUID();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentMethod method;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private PaymentStatus status = PaymentStatus.PENDING;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal amount;

    @Column(name = "amount_authorized", precision = 19, scale = 4)
    private BigDecimal amountAuthorized;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "provider_reference", length = 100)
    private String providerReference;

    @Column(name = "approval_code", length = 50)
    private String approvalCode;

    @Column(name = "terminal_reference", length = 100)
    private String terminalReference;

    @Column(name = "phone_number_masked", length = 30)
    private String phoneNumberMasked;

    @Column(name = "failure_reason", length = 100)
    private String failureReason;

    @Column(name = "failure_message", length = 500)
    private String failureMessage;

    @Column(name = "requested_at", nullable = false)
    private Instant requestedAt = Instant.now();

    @Column(name = "settled_at")
    private Instant settledAt;

    public SalePayment(PaymentMethod method, BigDecimal amount, String currency) {
        this.method = method;
        this.amount = amount;
        this.currency = currency == null ? "KES" : currency;
    }

    public void authorize(
            BigDecimal amountAuthorized, String providerReference, String approvalCode) {
        this.status = PaymentStatus.AUTHORIZED;
        this.amountAuthorized = amountAuthorized == null ? amount : amountAuthorized;
        this.providerReference = providerReference;
        this.approvalCode = approvalCode;
        this.settledAt = Instant.now();
    }

    public void fail(String reasonCode, String message) {
        this.status = PaymentStatus.FAILED;
        this.failureReason = reasonCode;
        this.failureMessage = message;
        this.settledAt = Instant.now();
    }

    /** Cash needs no provider: it is settled the moment it is in the drawer. */
    public boolean isSettledAtTheTill() {
        return method == PaymentMethod.CASH;
    }
}
