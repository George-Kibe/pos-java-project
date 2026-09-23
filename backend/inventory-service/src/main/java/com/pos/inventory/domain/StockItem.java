package com.pos.inventory.domain;

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
 * What a branch holds of one product.
 *
 * <p>{@code quantityOnHand} is a cache derived from the movement ledger, kept because a till cannot
 * sum a year of movements on every scan. It is never the only record of a change: anything that
 * moves this number writes a movement first, and a reconciliation can prove the two agree.
 *
 * <p>{@code productId} points into catalog's schema and is deliberately not a foreign key -
 * services do not share tables. The cached product details arrive on catalog.product-changed.
 */
@Entity
@Table(name = "stock_items")
// A page of adjustment lines loads its stock items in one query, not one per line.
@org.hibernate.annotations.BatchSize(size = 100)
@Getter
@Setter
@NoArgsConstructor
public class StockItem extends BaseEntity {

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "unit_of_measure", length = 20)
    private String unitOfMeasure;

    @Column(name = "quantity_on_hand", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityOnHand = BigDecimal.ZERO;

    /** Held for open carts. Reduces what may be promised, not what is on the shelf. */
    @Column(name = "quantity_reserved", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantityReserved = BigDecimal.ZERO;

    @Column(name = "reorder_point", precision = 19, scale = 3)
    private BigDecimal reorderPoint;

    @Column(name = "reorder_quantity", precision = 19, scale = 3)
    private BigDecimal reorderQuantity;

    @Column(name = "last_movement_at")
    private Instant lastMovementAt;

    public StockItem(UUID productId, UUID branchId) {
        this.productId = productId;
        this.branchId = branchId;
    }

    /** On hand minus what is already promised to open carts. */
    public BigDecimal quantityAvailable() {
        return quantityOnHand.subtract(quantityReserved);
    }

    public boolean isBelowReorderPoint() {
        return reorderPoint != null && quantityOnHand.compareTo(reorderPoint) <= 0;
    }

    /** Applies a signed movement to the cached figure. */
    public void applyMovement(BigDecimal signedQuantity, Instant occurredAt) {
        this.quantityOnHand = this.quantityOnHand.add(signedQuantity);
        this.lastMovementAt = occurredAt;
    }
}
