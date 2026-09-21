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

/** One product going back, at the cost it was received at. */
@Entity
@Table(name = "supplier_return_lines")
@Getter
@Setter
@NoArgsConstructor
public class SupplierReturnLine extends BaseEntity {

    private static final int MONEY_SCALE = 4;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_return_id", nullable = false)
    private SupplierReturn supplierReturn;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    SupplierReturnLine(
            SupplierReturn supplierReturn,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            String batchNumber,
            BigDecimal quantity,
            BigDecimal unitCost) {

        this.supplierReturn = supplierReturn;
        this.lineNumber = lineNumber;
        this.productId = productId;
        this.sku = sku;
        this.productName = productName;
        this.batchNumber = batchNumber;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.currency = supplierReturn.getCurrency();
        this.lineTotal = quantity.multiply(unitCost).setScale(MONEY_SCALE, RoundingMode.HALF_UP);
    }
}
