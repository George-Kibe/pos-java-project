package com.pos.catalog.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How a product is counted.
 *
 * <p>{@code allowsDecimal} is what stops a till accepting 0.5 tins of beans, and what lets it
 * accept 1.235 kilograms of tomatoes. Getting this wrong in either direction is visible to the
 * customer.
 */
@Entity
@Table(name = "units_of_measure")
@Getter
@Setter
@NoArgsConstructor
public class UnitOfMeasure extends BaseEntity {

    @Column(nullable = false, length = 20)
    private String code;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "allows_decimal", nullable = false)
    private boolean allowsDecimal = false;

    @Column(name = "decimal_places", nullable = false)
    private int decimalPlaces = 0;

    public UnitOfMeasure(String code, String name, boolean allowsDecimal, int decimalPlaces) {
        this.code = code;
        this.name = name;
        this.allowsDecimal = allowsDecimal;
        this.decimalPlaces = decimalPlaces;
    }
}
