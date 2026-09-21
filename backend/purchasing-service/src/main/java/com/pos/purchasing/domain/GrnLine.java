package com.pos.purchasing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One product on a delivery.
 *
 * <p>The batch number and expiry are captured here because this is the only moment a human is
 * holding the carton and can read what is printed on it. They travel to inventory unchanged; a
 * guess made later is how short-dated stock ends up sold last.
 */
@Entity
@Table(name = "grn_lines")
@Getter
@Setter
@NoArgsConstructor
public class GrnLine extends BaseEntity {

    private static final int MONEY_SCALE = 4;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "grn_id", nullable = false)
    private GoodsReceivedNote grn;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_line_id")
    private PurchaseOrderLine purchaseOrderLine;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "quantity_ordered", precision = 19, scale = 3)
    private BigDecimal quantityOrdered;

    @Column(name = "quantity_received", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityReceived;

    @Column(name = "quantity_rejected", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityRejected = BigDecimal.ZERO;

    @Column(name = "rejection_reason", length = 500)
    private String rejectionReason;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "landed_unit_cost", precision = 19, scale = 4)
    private BigDecimal landedUnitCost;

    @Column(name = "allocated_charges", nullable = false, precision = 19, scale = 4)
    private BigDecimal allocatedCharges = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    GrnLine(
            GoodsReceivedNote grn,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityReceived,
            BigDecimal unitCost,
            String batchNumber,
            LocalDate expiryDate) {

        this.grn = grn;
        this.lineNumber = lineNumber;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.quantityReceived = quantityReceived;
        this.unitCost = unitCost;
        this.batchNumber = batchNumber;
        this.expiryDate = expiryDate;
        this.currency = grn.getCurrency();
        this.lineTotal =
                quantityReceived.multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /**
     * The quantity that goes on the shelf.
     *
     * <p>Rejected goods were physically delivered but are not accepted - damaged on arrival,
     * already expired - so they are recorded and then excluded. Adding them to stock and writing
     * them off afterwards would put goods known to be bad into sellable stock, however briefly.
     */
    public BigDecimal quantityAccepted() {
        return quantityReceived.subtract(quantityRejected);
    }

    /** What the accepted goods cost before delivery charges. */
    public BigDecimal acceptedGoodsValue() {
        return quantityAccepted().multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** The difference against what was ordered, or null when this line had no order behind it. */
    public BigDecimal discrepancy() {
        return quantityOrdered == null ? null : quantityReceived.subtract(quantityOrdered);
    }
}
