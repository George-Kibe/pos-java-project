package com.pos.catalog.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One thing a promotion applies to. A promotion with several rules applies to any of them. */
@Entity
@Table(name = "promotion_rules")
@Getter
@Setter
@NoArgsConstructor
public class PromotionRule extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "promotion_id", nullable = false)
    private Promotion promotion;

    @Enumerated(EnumType.STRING)
    @Column(name = "scope_type", nullable = false, length = 20)
    private PromotionScope scopeType;

    @Column(name = "scope_id")
    private UUID scopeId;

    public PromotionRule(Promotion promotion, PromotionScope scopeType, UUID scopeId) {
        this.promotion = promotion;
        this.scopeType = scopeType;
        this.scopeId = scopeId;
    }

    public boolean covers(UUID productId, UUID categoryId) {
        return switch (scopeType) {
            case ALL -> true;
            case PRODUCT -> scopeId != null && scopeId.equals(productId);
            case CATEGORY -> scopeId != null && scopeId.equals(categoryId);
        };
    }
}
