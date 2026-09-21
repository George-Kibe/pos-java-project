package com.pos.inventory.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
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
import com.pos.inventory.domain.fefo.AvailableBatch;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * One delivery of one product at one branch.
 *
 * <p>Stock is held in batches rather than as a single number because expiry belongs to a delivery,
 * not to a product: Monday's milk and Thursday's milk are the same product and must not be sold in
 * the wrong order. Unit cost is per batch for the same reason - the margin on a sale depends on
 * what that delivery cost, not on an average.
 */
@Entity
@Table(name = "stock_batches")
@Getter
@Setter
@NoArgsConstructor
public class StockBatch extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "stock_item_id", nullable = false)
    private StockItem stockItem;

    @Column(name = "batch_number", nullable = false, length = 100)
    private String batchNumber;

    /** A calendar day, because that is what is stamped on the carton. */
    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(nullable = false, precision = 19, scale = 3)
    private BigDecimal quantity = BigDecimal.ZERO;

    @Column(name = "unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal unitCost = BigDecimal.ZERO;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "received_at", nullable = false)
    private Instant receivedAt = Instant.now();

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private BatchStatus status = BatchStatus.ACTIVE;

    @Column(name = "source_type", length = 30)
    private String sourceType;

    @Column(name = "source_id")
    private UUID sourceId;

    public StockBatch(
            StockItem stockItem,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currency) {
        this.stockItem = stockItem;
        this.batchNumber = batchNumber;
        this.expiryDate = expiryDate;
        this.quantity = quantity;
        this.unitCost = unitCost;
        this.currency = currency;
    }

    /** Takes stock out, marking the batch depleted once it is empty. */
    public void consume(BigDecimal amount) {
        this.quantity = this.quantity.subtract(amount);
        if (this.quantity.signum() <= 0) {
            this.quantity = BigDecimal.ZERO;
            // Kept rather than deleted: its movements still reference it, and the cost history
            // of past sales must remain resolvable.
            this.status = BatchStatus.DEPLETED;
        }
    }

    public void add(BigDecimal amount) {
        this.quantity = this.quantity.add(amount);
        if (this.status == BatchStatus.DEPLETED && this.quantity.signum() > 0) {
            this.status = BatchStatus.ACTIVE;
        }
    }

    public boolean isSellable() {
        return status == BatchStatus.ACTIVE && quantity.signum() > 0;
    }

    public AvailableBatch toAvailable() {
        return new AvailableBatch(
                getId(), batchNumber, expiryDate, receivedAt, quantity, unitCost, currency);
    }
}
