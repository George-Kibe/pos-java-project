package com.pos.sales.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One product in a basket, priced by catalog.
 *
 * <p>A voided line is flagged rather than deleted: the receipt shows what was scanned and then
 * removed, which is how a customer disputing a total is answered and how a cashier voiding items
 * all day becomes visible.
 */
@Entity
@Table(name = "cart_lines")
@Getter
@Setter
@NoArgsConstructor
public class CartLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "cart_id", nullable = false)
    private Cart cart;

    @Column(name = "line_number", nullable = false)
    private int lineNumber;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(length = 50)
    private String barcode;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 20)
    private PriceSource priceSource = PriceSource.BASE;

    @Column(name = "price_list_id")
    private UUID priceListId;

    @Column(name = "tax_inclusive", nullable = false)
    private boolean taxInclusive = true;

    @Column(name = "tax_class_code", length = 30)
    private String taxClassCode;

    @Column(name = "tax_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal taxRate = BigDecimal.ZERO;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal subtotal = BigDecimal.ZERO;

    @Column(name = "discount_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal discountTotal = BigDecimal.ZERO;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount = BigDecimal.ZERO;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "line_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal lineTotal = BigDecimal.ZERO;

    /** The promotions catalog applied, kept verbatim so the receipt can name them. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_discounts", columnDefinition = "jsonb")
    private String appliedDiscounts;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "overridden_by")
    private UUID overriddenBy;

    @Column(name = "original_unit_price", precision = 19, scale = 4)
    private BigDecimal originalUnitPrice;

    @Column(nullable = false)
    private boolean voided;

    @Column(name = "void_reason", length = 500)
    private String voidReason;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    public CartLine(Cart cart, int lineNumber, UUID productId, BigDecimal quantity) {
        this.cart = cart;
        this.lineNumber = lineNumber;
        this.productId = productId;
        this.quantity = quantity;
        this.currency = cart.getCurrency();
    }

    /** Marks the line removed, keeping it visible on the receipt and in the audit trail. */
    public void voidLine(String reason) {
        this.voided = true;
        this.voidReason = reason;
    }

    /** Whether a human set this price. */
    public boolean isOverridden() {
        return priceSource == PriceSource.OVERRIDE;
    }
}
