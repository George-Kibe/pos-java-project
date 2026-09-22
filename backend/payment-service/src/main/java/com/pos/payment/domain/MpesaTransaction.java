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

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One STK Push, keyed by Daraja's CheckoutRequestID.
 *
 * <p>{@code intentId} is null while a callback that beat its own push's response is parked here.
 */
@Entity
@Table(name = "mpesa_transactions")
@Getter
@Setter
@NoArgsConstructor
public class MpesaTransaction extends BaseEntity {

    public enum Status {
        PENDING,
        SUCCEEDED,
        FAILED
    }

    @Column(name = "intent_id")
    private UUID intentId;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "merchant_request_id", length = 100)
    private String merchantRequestId;

    @Column(name = "checkout_request_id", nullable = false, length = 100, updatable = false)
    private String checkoutRequestId;

    @Column(name = "phone_masked", length = 20)
    private String phoneMasked;

    @Column(name = "amount_requested", precision = 19, scale = 4)
    private BigDecimal amountRequested;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.PENDING;

    @Column(name = "result_code")
    private Integer resultCode;

    @Column(name = "result_desc", length = 500)
    private String resultDesc;

    @Column(name = "mpesa_receipt_number", length = 30)
    private String mpesaReceiptNumber;

    @Column(name = "amount_paid", precision = 19, scale = 4)
    private BigDecimal amountPaid;

    @Column(name = "transaction_date")
    private Instant transactionDate;

    @Column(name = "settled_by", length = 20)
    private String settledBy;

    @Column(name = "callback_received_at")
    private Instant callbackReceivedAt;

    @Column(name = "query_attempts", nullable = false)
    private int queryAttempts;

    @Column(name = "last_queried_at")
    private Instant lastQueriedAt;

    @Column(name = "next_query_at")
    private Instant nextQueryAt;

    public MpesaTransaction(String checkoutRequestId) {
        this.checkoutRequestId = checkoutRequestId;
    }

    public boolean isFinal() {
        return status != Status.PENDING;
    }

    /** Records the provider's answer, from whichever source delivered it first. */
    public void settle(int code, String description, String source) {
        this.resultCode = code;
        this.resultDesc =
                description == null || description.length() <= 500
                        ? description
                        : description.substring(0, 500);
        this.status = code == 0 ? Status.SUCCEEDED : Status.FAILED;
        this.settledBy = source;
        this.nextQueryAt = null;
    }
}
