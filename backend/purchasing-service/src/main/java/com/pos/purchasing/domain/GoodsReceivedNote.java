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
import com.pos.purchasing.domain.cost.AllocationBasis;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What actually turned up.
 *
 * <p>Kept separate from the order because the two disagree constantly: short deliveries, extra
 * cartons, substitutions, a pallet arriving a week late. A GRN may also stand alone, since
 * deliveries do arrive with no order behind them.
 *
 * <p>Freight and duty are charges on the delivery as a whole and are spread across the lines when
 * the GRN is posted. Nothing reaches inventory until then.
 */
@Entity
@Table(name = "goods_received_notes")
@Getter
@Setter
@NoArgsConstructor
public class GoodsReceivedNote extends BaseEntity {

    @Column(name = "grn_number", nullable = false, length = 30)
    private String grnNumber;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private GrnStatus status = GrnStatus.DRAFT;

    @Column(name = "delivery_note_ref", length = 50)
    private String deliveryNoteRef;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Column(name = "received_by")
    private UUID receivedBy;

    @Column(name = "posted_at")
    private Instant postedAt;

    @Column(name = "posted_by")
    private UUID postedBy;

    @Column(name = "freight_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal freightAmount = BigDecimal.ZERO;

    @Column(name = "duty_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal dutyAmount = BigDecimal.ZERO;

    @Enumerated(EnumType.STRING)
    @Column(name = "allocation_basis", nullable = false, length = 20)
    private AllocationBasis allocationBasis = AllocationBasis.BY_VALUE;

    @Column(name = "goods_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal goodsTotal = BigDecimal.ZERO;

    @Column(name = "landed_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal landedTotal = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(length = 1000)
    private String notes;

    @OneToMany(
            mappedBy = "grn",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    @OrderBy("lineNumber ASC")
    private List<GrnLine> lines = new ArrayList<>();

    public GoodsReceivedNote(String grnNumber, Supplier supplier, UUID branchId) {
        this.grnNumber = grnNumber;
        this.supplier = supplier;
        this.branchId = branchId;
        this.currency = supplier.getCurrency();
    }

    public GrnLine addLine(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityReceived,
            BigDecimal unitCost,
            String batchNumber,
            LocalDate expiryDate) {

        GrnLine line =
                new GrnLine(
                        this,
                        lines.size() + 1,
                        productId,
                        sku,
                        productName,
                        quantityReceived,
                        unitCost,
                        batchNumber,
                        expiryDate);
        lines.add(line);
        return line;
    }

    /** Freight plus duty: what has to be spread across the lines. */
    public BigDecimal totalCharges() {
        return freightAmount.add(dutyAmount);
    }

    public boolean isPosted() {
        return status == GrnStatus.POSTED;
    }
}
