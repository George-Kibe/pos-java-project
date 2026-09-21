package com.pos.inventory.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
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

/** One product on a transfer. */
@Entity
@Table(name = "stock_transfer_lines")
@Getter
@Setter
@NoArgsConstructor
public class StockTransferLine extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "transfer_id", nullable = false)
    private StockTransfer transfer;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "quantity_sent", nullable = false, precision = 19, scale = 3)
    private BigDecimal quantitySent;

    /**
     * What actually arrived.
     *
     * <p>May be less than was sent. A shortage found at the receiving end is a real event worth
     * recording, not an error to reconcile away.
     */
    @Column(name = "quantity_received", precision = 19, scale = 3)
    private BigDecimal quantityReceived;

    @Column(name = "batch_number", length = 100)
    private String batchNumber;

    @Column(name = "expiry_date")
    private LocalDate expiryDate;

    @Column(name = "unit_cost", precision = 19, scale = 4)
    private BigDecimal unitCost;

    public StockTransferLine(
            StockTransfer transfer, UUID productId, String sku, BigDecimal quantitySent) {
        this.transfer = transfer;
        this.productId = productId;
        this.sku = sku;
        this.quantitySent = quantitySent;
    }
}
