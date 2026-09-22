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

import org.hibernate.annotations.Fetch;
import org.hibernate.annotations.FetchMode;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What was sold, to whom, by whom, for how much.
 *
 * <p>Immutable once PAID apart from its status. A void or a refund is a new fact referencing this
 * row, never an edit of it - the receipt in the customer's hand has to stay answerable.
 *
 * <p>{@code clientSaleId} is the offline terminal's own id, unique here, which is what makes
 * replaying a queued batch safe. {@code priceVarianceFlagged} marks a sale whose client totals
 * disagreed with the server's revalidation: accepted, because the customer has gone, but never
 * quietly.
 */
@Entity
@Table(name = "sales")
@Getter
@Setter
@NoArgsConstructor
public class Sale extends BaseEntity {

    @Column(name = "receipt_number", length = 30)
    private String receiptNumber;

    @Column(name = "client_sale_id")
    private UUID clientSaleId;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "cart_id")
    private Cart cart;

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
    @Column(nullable = false, length = 25)
    private SaleStatus status = SaleStatus.PENDING;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SaleOrigin origin = SaleOrigin.ONLINE;

    @Column(name = "is_member", nullable = false)
    private boolean member;

    @Column(name = "net_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal netTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(name = "amount_tendered", precision = 19, scale = 4)
    private BigDecimal amountTendered;

    @Column(name = "change_given", precision = 19, scale = 4)
    private BigDecimal changeGiven;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "client_grand_total", precision = 19, scale = 4)
    private BigDecimal clientGrandTotal;

    @Column(name = "price_variance_flagged", nullable = false)
    private boolean priceVarianceFlagged;

    @Column(name = "price_variance_amount", precision = 19, scale = 4)
    private BigDecimal priceVarianceAmount;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    @Column(name = "completed_at")
    private Instant completedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(name = "voided_at")
    private Instant voidedAt;

    @Column(name = "voided_by")
    private UUID voidedBy;

    @Column(name = "void_approved_by")
    private UUID voidApprovedBy;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(name = "reservation_reference")
    private UUID reservationReference;

    @OneToMany(
            mappedBy = "sale",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<SaleLine> lines = new ArrayList<>();

    /**
     * Loaded by a separate select rather than joined. Two EAGER lists joined in one query is a
     * cartesian product Hibernate refuses outright ("cannot simultaneously fetch multiple bags").
     */
    @OneToMany(
            mappedBy = "sale",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    @Fetch(FetchMode.SELECT)
    private List<SalePayment> payments = new ArrayList<>();

    public Sale(UUID branchId, UUID cashierId) {
        this.branchId = branchId;
        this.cashierId = cashierId;
    }

    public SaleLine addLine(SaleLine line) {
        line.setSale(this);
        line.setLineNumber(lines.size() + 1);
        lines.add(line);
        return line;
    }

    public SalePayment addPayment(SalePayment payment) {
        payment.setSale(this);
        payments.add(payment);
        return payment;
    }

    /** What has actually been authorised so far, across every tender. */
    public BigDecimal authorizedTotal() {
        return payments.stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.AUTHORIZED)
                .map(
                        payment ->
                                payment.getAmountAuthorized() == null
                                        ? payment.getAmount()
                                        : payment.getAmountAuthorized())
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    /** Whether every shilling of the sale has been paid for. */
    public boolean isFullyPaid() {
        return authorizedTotal().compareTo(grandTotal) >= 0;
    }

    /** What is still owed. Never negative. */
    public BigDecimal outstanding() {
        BigDecimal outstanding = grandTotal.subtract(authorizedTotal());
        return outstanding.signum() < 0 ? BigDecimal.ZERO : outstanding;
    }

    /** The cash portion, which is what hits the drawer. */
    public BigDecimal cashPortion() {
        return payments.stream()
                .filter(payment -> payment.getStatus() == PaymentStatus.AUTHORIZED)
                .filter(
                        payment ->
                                payment.getMethod() == com.pos.events.payments.PaymentMethod.CASH)
                .map(SalePayment::getAmount)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
