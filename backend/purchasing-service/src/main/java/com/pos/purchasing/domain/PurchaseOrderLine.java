package com.pos.purchasing.domain;

import java.math.BigDecimal;
import java.math.RoundingMode;
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
 * One product on an order.
 *
 * <p>{@code quantityReceived} is a running total maintained as receipts post, so "is this order
 * complete" is answerable without summing every GRN on every list page.
 */
@Entity
@Table(name = "purchase_order_lines")
@Getter
@Setter
@NoArgsConstructor
public class PurchaseOrderLine extends BaseEntity {

    private static final int MONEY_SCALE = 4;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "purchase_order_id", nullable = false)
    private PurchaseOrder purchaseOrder;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "quantity_ordered", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityOrdered;

    @Column(name = "quantity_received", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityReceived = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "tax_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    PurchaseOrderLine(
            PurchaseOrder purchaseOrder,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityOrdered,
            BigDecimal unitCost,
            BigDecimal taxRate) {

        this.purchaseOrder = purchaseOrder;
        this.lineNumber = lineNumber;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.quantityOrdered = quantityOrdered;
        this.unitCost = unitCost;
        this.taxRate = taxRate == null ? BigDecimal.ZERO : taxRate;
        this.currency = purchaseOrder.getCurrency();
        recalculate();
    }

    /**
     * Line total and tax from quantity, cost and rate.
     *
     * <p>Purchase tax is added to the cost rather than extracted from it: a supplier quotes a net
     * price and adds VAT, which is the opposite of the shelf price a customer sees.
     */
    void recalculate() {
        this.lineTotal =
                quantityOrdered.multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
        this.taxAmount = lineTotal.multiply(taxRate).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }

    /** What is still owed on this line. Never negative, even after an over-delivery. */
    public BigDecimal quantityOutstanding() {
        BigDecimal outstanding = quantityOrdered.subtract(quantityReceived);
        return outstanding.signum() < 0 ? BigDecimal.ZERO : outstanding;
    }

    public boolean isFullyReceived() {
        return quantityReceived.compareTo(quantityOrdered) >= 0;
    }

    /** Records a delivery against this line. */
    public void receive(BigDecimal quantity) {
        this.quantityReceived = quantityReceived.add(quantity);
    }
}
