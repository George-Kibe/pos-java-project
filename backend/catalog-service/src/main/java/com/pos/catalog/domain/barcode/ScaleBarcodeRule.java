package com.pos.catalog.domain.barcode;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * How to read a barcode printed by a scale.
 *
 * <p>Configuration rather than code because there is no single standard: the prefix and the digit
 * positions vary by retailer and by scale vendor, and a shop that changes scales should not need a
 * release. Assuming one layout is the usual way this goes wrong - a barcode then decodes to the
 * wrong item or, worse, the right item at the wrong weight.
 */
@Entity
@Table(name = "scale_barcode_rules")
@Getter
@Setter
@NoArgsConstructor
public class ScaleBarcodeRule extends BaseEntity {

    /** Leading digits that mark a barcode as scale-printed. */
    @Column(nullable = false, length = 4)
    private String prefix;

    @Column(nullable = false, length = 100)
    private String name;

    @Column(name = "item_code_start", nullable = false)
    private int itemCodeStart;

    @Column(name = "item_code_length", nullable = false)
    private int itemCodeLength;

    @Column(name = "value_start", nullable = false)
    private int valueStart;

    @Column(name = "value_length", nullable = false)
    private int valueLength;

    @Enumerated(EnumType.STRING)
    @Column(name = "embedded_type", nullable = false, length = 10)
    private EmbeddedType embeddedType;

    /** 1000 to turn grams into kilograms, 100 to turn cents into units. */
    @Column(name = "value_divisor", nullable = false, precision = 19, scale = 4)
    private BigDecimal valueDivisor;

    @Column(nullable = false)
    private boolean active = true;

    /** What the digits in the value field mean. */
    public enum EmbeddedType {
        WEIGHT,
        PRICE
    }

    public ScaleBarcodeRule(
            String prefix,
            String name,
            int itemCodeStart,
            int itemCodeLength,
            int valueStart,
            int valueLength,
            EmbeddedType embeddedType,
            BigDecimal valueDivisor) {
        this.prefix = prefix;
        this.name = name;
        this.itemCodeStart = itemCodeStart;
        this.itemCodeLength = itemCodeLength;
        this.valueStart = valueStart;
        this.valueLength = valueLength;
        this.embeddedType = embeddedType;
        this.valueDivisor = valueDivisor;
    }
}
