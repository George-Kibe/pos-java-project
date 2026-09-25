package com.pos.inventory.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * What catalog last said about a product: the details a stock row shows. Kept from the
 * product-changed event, so a stock row made later - the product's first delivery to a branch -
 * starts with them.
 */
@Entity
@Table(name = "product_details")
@Getter
@Setter
@NoArgsConstructor
public class ProductDetails extends BaseEntity {

    @Column(name = "product_id", nullable = false, updatable = false)
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(length = 200)
    private String name;

    @Column(name = "unit_of_measure", length = 20)
    private String unitOfMeasure;

    public ProductDetails(UUID productId) {
        this.productId = productId;
    }
}
