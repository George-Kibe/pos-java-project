package com.pos.catalog.domain;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Currency;
import java.util.List;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.pos.common.money.Money;
import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** Something the shop sells. */
@Entity
@Table(name = "products")
@Getter
@Setter
@NoArgsConstructor
public class Product extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String sku;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(columnDefinition = "text")
    private String description;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "category_id", nullable = false)
    private Category category;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "brand_id")
    private Brand brand;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "unit_of_measure_id", nullable = false)
    private UnitOfMeasure unitOfMeasure;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tax_class_id", nullable = false)
    private TaxClass taxClass;

    /**
     * Priced per unit of measure; the till takes the quantity from a scale rather than counting.
     */
    @Column(name = "sell_by_weight", nullable = false)
    private boolean sellByWeight = false;

    /**
     * Whether {@link #basePrice} already contains tax.
     *
     * <p>Per product rather than global: a supermarket shows shelf prices inclusive, while some
     * lines - wholesale packs, services - are quoted exclusive. Getting this wrong misprices every
     * sale of the item by the tax rate.
     */
    @Column(name = "price_includes_tax", nullable = false)
    private boolean priceIncludesTax = true;

    @Column(name = "base_price", nullable = false, precision = 19, scale = 4)
    private BigDecimal basePrice;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(nullable = false)
    private boolean active = true;

    @Column(name = "reorder_point", precision = 19, scale = 3)
    private BigDecimal reorderPoint;

    @Column(name = "reorder_quantity", precision = 19, scale = 3)
    private BigDecimal reorderQuantity;

    @Column(name = "image_url", length = 500)
    private String imageUrl;

    /** The image's object key in storage; null when the product has no uploaded image. */
    @Column(name = "image_key", length = 300)
    private String imageKey;

    @OneToMany(
            mappedBy = "product",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    // Fetched for a whole page of products in one query, not one query per product.
    @org.hibernate.annotations.BatchSize(size = 100)
    private List<ProductBarcode> barcodes = new ArrayList<>();

    public Money basePriceAsMoney() {
        return Money.of(basePrice, Currency.getInstance(currency));
    }

    public void addBarcode(String barcode, boolean primary) {
        ProductBarcode entry = new ProductBarcode(this, barcode, primary);
        barcodes.add(entry);
    }
}
