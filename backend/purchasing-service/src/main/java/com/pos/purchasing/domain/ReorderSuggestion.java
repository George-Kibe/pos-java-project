package com.pos.purchasing.domain;

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

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * "You are about to run out of this."
 *
 * <p>Stored rather than computed per request so that a dismissal sticks. A suggestion list that
 * keeps re-proposing what a buyer has already rejected is a list nobody reads, and then the one
 * suggestion that mattered goes unseen too.
 */
@Entity
@Table(name = "reorder_suggestions")
@Getter
@Setter
@NoArgsConstructor
public class ReorderSuggestion extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "supplier_id")
    private Supplier supplier;

    @Column(name = "quantity_on_hand", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityOnHand;

    @Column(name = "reorder_point", precision = 19, scale = 3)
    private BigDecimal reorderPoint;

    @Column(name = "suggested_quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal suggestedQuantity;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SuggestionStatus status = SuggestionStatus.OPEN;

    @Column(name = "dismissed_reason", length = 500)
    private String dismissedReason;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    public ReorderSuggestion(
            UUID productId,
            UUID branchId,
            BigDecimal quantityOnHand,
            BigDecimal suggestedQuantity) {
        this.productId = productId;
        this.branchId = branchId;
        this.quantityOnHand = quantityOnHand;
        this.suggestedQuantity = suggestedQuantity;
    }

    /** What ordering the suggested quantity would cost, or null when no cost is known. */
    public BigDecimal estimatedValue() {
        return unitCost == null ? null : suggestedQuantity.multiply(unitCost);
    }
}
