package com.pos.purchasing.domain;

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
 * Goods going back to the supplier.
 *
 * <p>References the receipt it reverses, so the credit claimed is the cost that was booked rather
 * than today's price - which matters when a cost has moved between delivery and return.
 */
@Entity
@Table(name = "supplier_returns")
@Getter
@Setter
@NoArgsConstructor
public class SupplierReturn extends BaseEntity {

    @Column(name = "return_number", nullable = false, length = 30)
    private String returnNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grn_id")
    private GoodsReceivedNote grn;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierReturnStatus status = SupplierReturnStatus.DRAFT;

    @Enumerated(EnumType.STRING)
    @Column(name = "reason_code", nullable = false, length = 30)
    private ReturnReason reasonCode;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "sent_at")
    private Instant sentAt;

    @Column(name = "credited_at")
    private Instant creditedAt;

    @Column(name = "credit_note_ref", length = 50)
    private String creditNoteRef;

    @Column(length = 1000)
    private String notes;

    @OneToMany(
            mappedBy = "supplierReturn",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<SupplierReturnLine> lines = new ArrayList<>();

    public SupplierReturn(
            String returnNumber, Supplier supplier, UUID branchId, ReturnReason reasonCode) {
        this.returnNumber = returnNumber;
        this.supplier = supplier;
        this.branchId = branchId;
        this.reasonCode = reasonCode;
        this.currency = supplier.getCurrency();
    }

    public SupplierReturnLine addLine(
            UUID productId,
            String sku,
            String productName,
            String batchNumber,
            BigDecimal quantity,
            BigDecimal unitCost) {

        SupplierReturnLine line =
                new SupplierReturnLine(
                        this,
                        lines.size() + 1,
                        productId,
                        sku,
                        productName,
                        batchNumber,
                        quantity,
                        unitCost);
        lines.add(line);
        return line;
    }

    public void recalculateTotal() {
        this.totalAmount =
                lines.stream()
                        .map(SupplierReturnLine::getLineTotal)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
