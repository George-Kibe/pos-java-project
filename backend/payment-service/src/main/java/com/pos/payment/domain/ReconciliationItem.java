package com.pos.payment.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "reconciliation_items")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationItem extends BaseEntity {

    @Column(name = "run_id", nullable = false, updatable = false)
    private UUID runId;

    @Column(nullable = false, length = 30)
    private String kind;

    @Column(name = "mpesa_receipt_number", length = 30)
    private String mpesaReceiptNumber;

    @Column(name = "payment_id")
    private UUID paymentId;

    @Column(name = "statement_amount", precision = 19, scale = 4)
    private BigDecimal statementAmount;

    @Column(name = "recorded_amount", precision = 19, scale = 4)
    private BigDecimal recordedAmount;

    @Column(precision = 19, scale = 4)
    private BigDecimal difference;

    @Column(length = 500)
    private String note;
}
