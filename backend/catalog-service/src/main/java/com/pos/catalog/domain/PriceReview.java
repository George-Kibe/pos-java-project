package com.pos.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.pos.catalog.domain.pricing.MarginCheck;
import com.pos.catalog.domain.pricing.PriceSource;
import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A delivery whose cost left an item earning less than its target at a branch - or less than it
 * cost - waiting for a manager to set a new price or keep the old one.
 *
 * <p>The figures are those of the check, so the review reads the same after the price or the cost
 * has moved. Nothing changes a price by itself: a review only proposes.
 */
@Entity
@Table(name = "price_reviews")
@Getter
@Setter
@NoArgsConstructor
public class PriceReview extends BaseEntity {

    public enum Status {
        OPEN,
        /** The suggested (or another) price was set. */
        ACCEPTED,
        /** The old price stays, for a reason given. */
        KEPT,
        /** A later delivery at the branch replaced it. */
        SUPERSEDED
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id")
    private Product product;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(name = "receipt_id", nullable = false)
    private UUID receiptId;

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(name = "price_includes_tax", nullable = false)
    private boolean priceIncludesTax;

    @Column(name = "tax_rate", nullable = false, precision = 9, scale = 6)
    private BigDecimal taxRate;

    @Enumerated(EnumType.STRING)
    @Column(name = "price_source", nullable = false, length = 20)
    private PriceSource priceSource;

    @Column(name = "price_list_id")
    private UUID priceListId;

    @Column(precision = 9, scale = 4)
    private BigDecimal margin;

    @Column(name = "target_margin", precision = 7, scale = 4)
    private BigDecimal targetMargin;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private MarginCheck.Status finding;

    @Column(name = "suggested_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal suggestedPrice;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Status status = Status.OPEN;

    @Column(name = "decided_by")
    private UUID decidedBy;

    @Column(name = "decided_at")
    private Instant decidedAt;

    @Column(name = "new_price", precision = 19, scale = 4)
    private BigDecimal newPrice;

    @Column(length = 500)
    private String reason;

    public boolean isOpen() {
        return status == Status.OPEN;
    }
}
