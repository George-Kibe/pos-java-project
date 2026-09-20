package com.pos.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;

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
 * A rate and the period it applies to.
 *
 * <p>The upper bound is exclusive, so consecutive periods abut without overlapping and an instant
 * always resolves to exactly one rate. A database exclusion constraint enforces that no two periods
 * for one class can overlap, because two rates in force at once would make tax non-deterministic.
 */
@Entity
@Table(name = "tax_rates")
@Getter
@Setter
@NoArgsConstructor
public class TaxRate extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "tax_class_id", nullable = false)
    private TaxClass taxClass;

    /** 0.160000 for 16%. */
    @Column(nullable = false, precision = 9, scale = 6)
    private BigDecimal rate;

    @Column(name = "valid_from", nullable = false)
    private Instant validFrom;

    /** Null means still in force. */
    @Column(name = "valid_to")
    private Instant validTo;

    public TaxRate(TaxClass taxClass, BigDecimal rate, Instant validFrom) {
        this.taxClass = taxClass;
        this.rate = rate;
        this.validFrom = validFrom;
    }

    public boolean appliesAt(Instant at) {
        return !at.isBefore(validFrom) && (validTo == null || at.isBefore(validTo));
    }
}
