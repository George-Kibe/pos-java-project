package com.pos.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;
import com.pos.sales.domain.policy.TillReconciliation;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One cashier, one register, one shift.
 *
 * <p>The running cash figures are maintained as sales and refunds happen rather than summed at
 * close, so a supervisor can see mid-shift what the drawer should hold - which is when a
 * discrepancy is still cheap to explain.
 */
@Entity
@Table(name = "till_sessions")
@Getter
@Setter
@NoArgsConstructor
public class TillSession extends BaseEntity {

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "register_id", nullable = false)
    private UUID registerId;

    @Column(name = "cashier_id", nullable = false)
    private UUID cashierId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private TillSessionStatus status = TillSessionStatus.OPEN;

    @Column(name = "opened_at", nullable = false)
    private Instant openedAt = Instant.now();

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "closed_by")
    private UUID closedBy;

    @Column(name = "opening_float", nullable = false, precision = 19, scale = 4)
    private BigDecimal openingFloat = BigDecimal.ZERO;

    @Column(name = "cash_sales", nullable = false, precision = 19, scale = 4)
    private BigDecimal cashSales = BigDecimal.ZERO;

    @Column(name = "cash_refunds", nullable = false, precision = 19, scale = 4)
    private BigDecimal cashRefunds = BigDecimal.ZERO;

    @Column(name = "cash_drops", nullable = false, precision = 19, scale = 4)
    private BigDecimal cashDrops = BigDecimal.ZERO;

    @Column(name = "non_cash_sales", nullable = false, precision = 19, scale = 4)
    private BigDecimal nonCashSales = BigDecimal.ZERO;

    @Column(name = "expected_cash", precision = 19, scale = 4)
    private BigDecimal expectedCash;

    @Column(name = "counted_cash", precision = 19, scale = 4)
    private BigDecimal countedCash;

    @Column(precision = 19, scale = 4)
    private BigDecimal variance;

    @Column(name = "sale_count", nullable = false)
    private int saleCount;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(length = 1000)
    private String notes;

    public TillSession(UUID branchId, UUID registerId, UUID cashierId, BigDecimal openingFloat) {
        this.branchId = branchId;
        this.registerId = registerId;
        this.cashierId = cashierId;
        this.openingFloat = openingFloat == null ? BigDecimal.ZERO : openingFloat;
    }

    /** Records a completed sale against the shift, split by whether it put cash in the drawer. */
    public void recordSale(BigDecimal cashPortion, BigDecimal nonCashPortion) {
        this.cashSales = cashSales.add(cashPortion);
        this.nonCashSales = nonCashSales.add(nonCashPortion);
        this.saleCount = saleCount + 1;
    }

    /** Records cash paid back out to a customer. */
    public void recordRefund(BigDecimal cashPortion) {
        this.cashRefunds = cashRefunds.add(cashPortion);
    }

    public void recordDrop(BigDecimal amount) {
        this.cashDrops = cashDrops.add(amount);
    }

    public void addFloat(BigDecimal amount) {
        this.openingFloat = openingFloat.add(amount);
    }

    /** What the drawer should hold right now, whether or not the shift is closing. */
    public TillReconciliation reconcile(BigDecimal counted) {
        return TillReconciliation.of(openingFloat, cashSales, cashRefunds, cashDrops, counted);
    }
}
