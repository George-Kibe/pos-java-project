package com.pos.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
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

/** One product on a count sheet. */
@Entity
@Table(name = "stock_take_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockTakeLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_take_id", nullable = false)
    private StockTake stockTake;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    /** What the system believed when counting began. */
    @Column(name = "snapshot_quantity", nullable = false, precision = 19, scale = 3)
    private BigDecimal snapshotQuantity;

    /** What was actually on the shelf. Null until somebody counts it. */
    @Column(name = "counted_quantity", precision = 19, scale = 3)
    private BigDecimal countedQuantity;

    @Column(name = "counted_at")
    private Instant countedAt;

    @Column(name = "counted_by")
    private UUID countedBy;

    @Column(length = 255)
    private String notes;

    public StockTakeLine(StockTake stockTake, StockItem stockItem) {
        this.stockTake = stockTake;
        this.stockItem = stockItem;
        this.productId = stockItem.getProductId();
        this.sku = stockItem.getSku();
        this.snapshotQuantity = stockItem.getQuantityOnHand();
    }

    public boolean isCounted() {
        return countedQuantity != null;
    }

    /** Counted minus believed. Positive means more was found than expected. */
    public BigDecimal variance() {
        return isCounted() ? countedQuantity.subtract(snapshotQuantity) : BigDecimal.ZERO;
    }

    public boolean hasVariance() {
        return isCounted() && variance().signum() != 0;
    }
}
