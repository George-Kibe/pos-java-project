package com.pos.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
 * A commitment to buy.
 *
 * <p>The status machine in {@link PurchaseOrderStatus} is enforced here rather than trusted to
 * callers, and {@code approvedTotal} freezes the figure that was authorised. Without that freeze a
 * line edited after approval silently raises the amount someone signed for, which is the oldest
 * purchasing fraud there is.
 */
@Entity
@Table(name = "purchase_orders")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrder extends BaseEntity {

    @Column(name = "order_number", nullable = false, length = 30)
    private String orderNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 25)
    private PurchaseOrderStatus status = PurchaseOrderStatus.DRAFT;

    @Column(name = "order_date", nullable = false)
    private LocalDate orderDate = LocalDate.now();

    @Column(name = "expected_delivery_date")
    private LocalDate expectedDeliveryDate;

    @Column(name = "net_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal netTotal = BigDecimal.ZERO;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal = BigDecimal.ZERO;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "approved_total", precision = 19, scale = 4)
    private BigDecimal approvedTotal;

    @Column(name = "submitted_by")
    private UUID submittedBy;

    @Column(name = "submitted_at")
    private Instant submittedAt;

    @Column(name = "approved_by")
    private UUID approvedBy;

    @Column(name = "approved_at")
    private Instant approvedAt;

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "closed_at")
    private Instant closedAt;

    @Column(name = "cancelled_at")
    private Instant cancelledAt;

    @Column(name = "cancellation_reason", length = 500)
    private String cancellationReason;

    @Column(length = 1000)
    private String notes;

    @OneToMany(
            mappedBy = "purchaseOrder",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<PurchaseOrderLine> lines = new ArrayList<>();

    public PurchaseOrder(String orderNumber, Supplier supplier, UUID branchId) {
        this.orderNumber = orderNumber;
        this.supplier = supplier;
        this.branchId = branchId;
        this.currency = supplier.getCurrency();
    }

    public PurchaseOrderLine addLine(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal taxRate) {

        PurchaseOrderLine line =
                new PurchaseOrderLine(
                        this,
                        lines.size() + 1,
                        productId,
                        sku,
                        productName,
                        quantity,
                        unitCost,
                        taxRate);
        lines.add(line);
        return line;
    }

    /** Recomputes the order's totals from its lines. Always derived, never set from outside. */
    public void recalculateTotals() {
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal tax = BigDecimal.ZERO;
        for (PurchaseOrderLine line : lines) {
            line.recalculate();
            net = net.add(line.getLineTotal());
            tax = tax.add(line.getTaxAmount());
        }
        this.netTotal = net;
        this.taxTotal = tax;
        this.grandTotal = net.add(tax);
    }

    /** True once every line has had its full ordered quantity delivered. */
    public boolean isFullyReceived() {
        return !lines.isEmpty() && lines.stream().allMatch(PurchaseOrderLine::isFullyReceived);
    }

    /** True when something has arrived but not everything. */
    public boolean isPartiallyReceived() {
        return lines.stream().anyMatch(line -> line.getQuantityReceived().signum() > 0)
                && !isFullyReceived();
    }
}
