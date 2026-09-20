package com.pos.catalog.domain;

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
 * One of possibly several barcodes for a product.
 *
 * <p>Several because a case, an inner and a single of the same item carry different barcodes, and
 * manufacturers reissue them. Scanning any of them has to find the product, or the cashier types
 * the SKU and the queue grows.
 */
@Entity
@Table(name = "product_barcodes")
@Getter
@Setter
@NoArgsConstructor
public class ProductBarcode extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, length = 64)
    private String barcode;

    @Column(name = "is_primary", nullable = false)
    private boolean primary = false;

    public ProductBarcode(Product product, String barcode, boolean primary) {
        this.product = product;
        this.barcode = barcode;
        this.primary = primary;
    }
}
