package com.pos.sales.domain;

import java.math.BigDecimal;
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
 * One product coming back.
 *
 * <p>{@code resaleable} is the flag inventory acts on, and it is the cashier's judgement with the
 * goods in hand. Defaulting it to true is how a shop comes to resell something it already knows is
 * broken, so it is carried explicitly all the way to the event.
 */
@Entity
@Table(name = "return_lines")
@Getter
@Setter
@NoArgsConstructor
public class SaleReturnLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "return_id", nullable = false)
    private SaleReturn saleReturn;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_line_id", nullable = false)
    private SaleLine saleLine;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "tax_class_code", length = 30)
    private String taxClassCode;

    @Column(name = "tax_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "refund_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal refundAmount = BigDecimal.ZERO;

    @Column(nullable = false)
    private boolean resaleable = true;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "condition_note", length = 500)
    private String conditionNote;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    public SaleReturnLine(SaleLine saleLine, BigDecimal quantity, boolean resaleable) {
        this.saleLine = saleLine;
        this.productId = saleLine.getProductId();
        this.sku = saleLine.getSku();
        this.productName = saleLine.getProductName();
        this.quantity = quantity;
        this.unitPrice = saleLine.getUnitPrice();
        this.taxClassCode = saleLine.getTaxClassCode();
        this.taxRate = saleLine.getTaxRate();
        this.resaleable = resaleable;
        this.batchNumber = saleLine.getBatchNumber();
        this.currency = saleLine.getCurrency();
    }
}
