package com.pos.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A price a human decided on.
 *
 * <p>Its own table as well as columns on the line, because "who has been overriding prices" is a
 * question asked across sales rather than within one. {@code valueGivenAway} is stored rather than
 * derived so a report can rank by it directly - that figure is the reason this table exists.
 */
@Entity
@Table(name = "price_overrides")
@Getter
@Setter
@NoArgsConstructor
public class PriceOverride extends BaseEntity {

    @Column(name = "cart_id")
    private UUID cartId;

    @Column(name = "cart_line_id")
    private UUID cartLineId;

    @Column(name = "sale_id")
    private UUID saleId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "original_unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal originalUnitPrice;

    @Column(name = "new_unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal newUnitPrice;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "value_given_away", nullable = false, precision = 19, scale = 4)
    private BigDecimal valueGivenAway;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(nullable = false, length = 500)
    private String reason;

    @Column(name = "approved_by", nullable = false)
    private UUID approvedBy;

    @Column(name = "cashier_id")
    private UUID cashierId;

    @Column(name = "occurred_at", nullable = false)
    private Instant occurredAt = Instant.now();

    public PriceOverride(
            CartLine line,
            BigDecimal originalUnitPrice,
            BigDecimal newUnitPrice,
            String reason,
            UUID approvedBy) {

        this.cartId = line.getCart().getId();
        this.cartLineId = line.getId();
        this.branchId = line.getCart().getBranchId();
        this.productId = line.getProductId();
        this.sku = line.getSku();
        this.originalUnitPrice = originalUnitPrice;
        this.newUnitPrice = newUnitPrice;
        this.quantity = line.getQuantity();
        // Positive when the shop gave something up, which is the direction that gets reported.
        this.valueGivenAway = originalUnitPrice.subtract(newUnitPrice).multiply(line.getQuantity());
        this.reason = reason;
        this.approvedBy = approvedBy;
        this.cashierId = line.getCart().getCashierId();
        this.currency = line.getCurrency();
    }
}
