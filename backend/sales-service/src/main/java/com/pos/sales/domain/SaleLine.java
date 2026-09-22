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
 * One line as charged, snapshotted.
 *
 * <p>Deliberately duplicates the cart line. A reprint months later must show what the customer
 * paid, and {@code unitCost} records what the goods were worth at that moment - which changes with
 * every delivery, so a margin computed from today's cost would be fiction.
 */
@Entity
@Table(name = "sale_lines")
@Getter
@Setter
@NoArgsConstructor
public class SaleLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

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

    @Column(name = "quantity_returned", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityReturned = BigDecimal.ZERO;

    @Column(name = "unit_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitPrice;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 20)
    private PriceSource priceSource = PriceSource.BASE;

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

    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "applied_discounts", columnDefinition = "jsonb")
    private String appliedDiscounts;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "overridden_by")
    private UUID overriddenBy;

    @Column(name = "original_unit_price", precision = 19, scale = 4)
    private BigDecimal originalUnitPrice;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    /** Builds a sale line from the cart line it was rung up as. */
    public static SaleLine from(CartLine cartLine) {
        SaleLine line = new SaleLine();
        line.productId = cartLine.getProductId();
        line.sku = cartLine.getSku();
        line.productName = cartLine.getProductName();
        line.barcode = cartLine.getBarcode();
        line.quantity = cartLine.getQuantity();
        line.unitPrice = cartLine.getUnitPrice();
        line.priceSource = cartLine.getPriceSource();
        line.taxInclusive = cartLine.isTaxInclusive();
        line.taxClassCode = cartLine.getTaxClassCode();
        line.taxRate = cartLine.getTaxRate();
        line.subtotal = cartLine.getSubtotal();
        line.discountTotal = cartLine.getDiscountTotal();
        line.netAmount = cartLine.getNetAmount();
        line.taxAmount = cartLine.getTaxAmount();
        line.lineTotal = cartLine.getLineTotal();
        line.appliedDiscounts = cartLine.getAppliedDiscounts();
        line.overrideReason = cartLine.getOverrideReason();
        line.overriddenBy = cartLine.getOverriddenBy();
        line.originalUnitPrice = cartLine.getOriginalUnitPrice();
        line.currency = cartLine.getCurrency();
        return line;
    }

    /** How much of this line has not been returned. */
    public BigDecimal quantityRemaining() {
        return quantity.subtract(quantityReturned);
    }

    public void recordReturn(BigDecimal returned) {
        this.quantityReturned = quantityReturned.add(returned);
    }
}
