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

/** A place in the product hierarchy. Promotions and reports both group by it. */
@Entity
@Table(name = "categories")
@Getter
@Setter
@NoArgsConstructor
public class Category extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "parent_id")
    private Category parent;

    @Column(nullable = false)
    private boolean active = true;

    /**
     * What items in this category should earn, as a fraction of the price without tax; null
     * inherits the parent's. See {@link com.pos.catalog.domain.pricing.MarginCheck}.
     */
    @Column(name = "target_margin", precision = 7, scale = 4)
    private java.math.BigDecimal targetMargin;

    /** This category's target, or the nearest ancestor's; null when none is set anywhere. */
    public java.math.BigDecimal effectiveTargetMargin() {
        for (Category at = this; at != null; at = at.getParent()) {
            if (at.getTargetMargin() != null) {
                return at.getTargetMargin();
            }
        }
        return null;
    }

    public Category(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
