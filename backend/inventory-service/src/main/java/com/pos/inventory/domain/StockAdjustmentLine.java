package com.pos.inventory.domain;

import java.math.BigDecimal;

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

/** One product's correction within an adjustment. */
@Entity
@Table(name = "stock_adjustment_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockAdjustmentLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "adjustment_id", nullable = false)
    private StockAdjustment adjustment;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    /** Optional: an adjustment may target a specific batch, or the item as a whole. */
    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "batch_id")
    private StockBatch batch;

    /** Signed, like a movement. */
    @Column(name = "quantity_delta", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityDelta;

    @Column(length = 255)
    private String notes;

    public StockAdjustmentLine(
            StockAdjustment adjustment,
            StockItem stockItem,
            StockBatch batch,
            BigDecimal quantityDelta,
            String notes) {
        this.adjustment = adjustment;
        this.stockItem = stockItem;
        this.batch = batch;
        this.quantityDelta = quantityDelta;
        this.notes = notes;
    }
}
