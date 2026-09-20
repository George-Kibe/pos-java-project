package com.pos.catalog.domain;

import java.math.BigDecimal;
import java.util.Currency;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.pos.common.money.Money;
import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One product's price on one price list. */
@Entity
@Table(name = "price_list_items")
@Getter
@Setter
@NoArgsConstructor
public class PriceListItem extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "price_list_id", nullable = false)
    private PriceList priceList;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "product_id", nullable = false)
    private Product product;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal price;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    public PriceListItem(PriceList priceList, Product product, BigDecimal price) {
        this.priceList = priceList;
        this.product = product;
        this.price = price;
        this.currency = product.getCurrency();
    }

    public Money priceAsMoney() {
        return Money.of(price, Currency.getInstance(currency));
    }
}
