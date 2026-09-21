package com.pos.purchasing.domain;

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

/**
 * A product one supplier sells us, and what it costs.
 *
 * <p>{@code agreedUnitCost} is the negotiated price; {@code lastUnitCost} is what the most recent
 * delivery actually charged. Keeping both is the point - the gap between them is the cost creep
 * that nobody announces, and it is what triggers {@code supplier-cost-changed}.
 */
@Entity
@Table(name = "supplier_products")
@Getter
@Setter
@NoArgsConstructor
public class SupplierProduct extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @Column(name = "product_id", nullable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(name = "product_name", length = 200)
    private String productName;

    @Column(name = "supplier_sku", length = 50)
    private String supplierSku;

    @Column(name = "agreed_unit_cost", nullable = false, precision = 19, scale = 4)
    private BigDecimal agreedUnitCost;

    @Column(name = "last_unit_cost", precision = 19, scale = 4)
    private BigDecimal lastUnitCost;

    @Column(name = "last_received_at")
    private Instant lastReceivedAt;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "minimum_order_qty", nullable = false, precision = 19, scale = 3)
    private BigDecimal minimumOrderQty = BigDecimal.ONE;

    @Column(name = "lead_time_days")
    private Integer leadTimeDays;

    @Column(name = "is_preferred", nullable = false)
    private boolean preferred;

    public SupplierProduct(
            Supplier supplier, UUID productId, String sku, BigDecimal agreedUnitCost) {
        this.supplier = supplier;
        this.productId = productId;
        this.sku = sku;
        this.agreedUnitCost = agreedUnitCost;
        this.currency = supplier.getCurrency();
    }

    /**
     * How long this product takes to arrive, falling back to the supplier's general lead time.
     *
     * <p>Per product because a supplier who delivers bread next day may take three weeks over an
     * imported line, and ordering the second on the first's timetable empties the shelf.
     */
    public int effectiveLeadTimeDays() {
        return leadTimeDays != null ? leadTimeDays : supplier.getLeadTimeDays();
    }
}
