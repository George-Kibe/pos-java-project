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

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A basket in progress.
 *
 * <p>Separate from a sale because most baskets never become one, and because a cart is mutable by
 * nature while a sale must not be. The totals here are a convenience for the lane display; the
 * figures that matter are recomputed from catalog at checkout and snapshotted onto the sale.
 */
@Entity
@Table(name = "carts")
@Getter
@Setter
@NoArgsConstructor
public class Cart extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "till_session_id")
    private TillSession tillSession;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "register_id")
    private UUID registerId;

    @Column(name = "cashier_id")
    private UUID cashierId;

    @Column(name = "customer_id")
    private UUID customerId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private CartStatus status = CartStatus.OPEN;

    @Column(name = "suspend_code", length = 12)
    private String suspendCode;

    @Column(name = "suspended_at")
    private Instant suspendedAt;

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

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @OneToMany(
            mappedBy = "cart",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<CartLine> lines = new ArrayList<>();

    public Cart(TillSession tillSession, UUID branchId) {
        this.tillSession = tillSession;
        this.branchId = branchId;
        if (tillSession != null) {
            this.registerId = tillSession.getRegisterId();
            this.cashierId = tillSession.getCashierId();
            this.currency = tillSession.getCurrency();
        }
    }

    /** The next line number, counting voided lines so numbering never repeats on a receipt. */
    public int nextLineNumber() {
        return lines.stream().mapToInt(CartLine::getLineNumber).max().orElse(0) + 1;
    }

    /** The lines that are actually being bought. */
    public List<CartLine> activeLines() {
        return lines.stream().filter(line -> !line.isVoided()).toList();
    }
}
