package com.pos.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.OrderBy;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;
import com.pos.events.payments.PaymentMethod;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A refund.
 *
 * <p>Named {@code SaleReturn} because {@code Return} is a keyword-adjacent name that reads badly in
 * every call site. It references the sale it reverses rather than editing it, so the original
 * receipt stays answerable, and it records whether the returns window was broken and who decided to
 * accept it anyway.
 */
@Entity
@Table(name = "returns")
@Getter
@Setter
@NoArgsConstructor
public class SaleReturn extends BaseEntity {

    @Column(name = "return_number", nullable = false, length = 30)
    private String returnNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "original_sale_id", nullable = false)
    private Sale originalSale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "till_session_id")
    private TillSession tillSession;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "register_id")
    private UUID registerId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private ReturnStatus status = ReturnStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ReturnReason reasonCode;

    @Column(length = 1000)
    private String notes;

    @Enumerated(EnumType.STRING)
    @Column(name = "refund_method", nullable = false, length = 20)
    private PaymentMethod refundMethod = PaymentMethod.CASH;

    @Column(name = "refund_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundTotal = BigDecimal.ZERO;

    @Column(name = "tax_refunded", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxRefunded = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "days_since_sale")
    private Integer daysSinceSale;

    @Column(name = "outside_policy_window", nullable = false)
    private boolean outsidePolicyWindow;

    @Column(name = "policy_override_by")
    private UUID policyOverrideBy;

    @Column(name = "policy_override_reason", length = 500)
    private String policyOverrideReason;

    @Column(name = "completed_at")
    private Instant completedAt;

    @OneToMany(
            mappedBy = "saleReturn",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<SaleReturnLine> lines = new ArrayList<>();

    public SaleReturn(String returnNumber, Sale originalSale, UUID cashierId, ReturnReason reason) {
        this.returnNumber = returnNumber;
        this.originalSale = originalSale;
        this.branchId = originalSale.getBranchId();
        this.registerId = originalSale.getRegisterId();
        this.customerId = originalSale.getCustomerId();
        this.cashierId = cashierId;
        this.reasonCode = reason;
        this.currency = originalSale.getCurrency();
    }

    public SaleReturnLine addLine(SaleReturnLine line) {
        line.setSaleReturn(this);
        line.setLineNumber(lines.size() + 1);
        lines.add(line);
        return line;
    }

    /** Sums the lines. The refund total is never set from outside. */
    public void recalculateTotals() {
        BigDecimal refund = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (SaleReturnLine line : lines) {
            refund = refund.add(line.getRefundAmount());
            tax = tax.add(line.getTaxAmount());
        }
        this.refundTotal = refund;
        this.taxRefunded = tax;
    }

    /** Whether any of the goods are fit to sell again. */
    public boolean hasResaleableGoods() {
        return lines.stream().anyMatch(SaleReturnLine::isResaleable);
    }
}
