package com.pos.catalog.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A price reduction and the conditions under which it applies.
 *
 * <p>{@code priority} and {@code stackable} exist to make the outcome deterministic. Without them
 * the discount a customer receives depends on the order rows come back from the database, and two
 * tills can price the same basket differently - which is the sort of thing that ends up being
 * explained to a regulator.
 */
@Entity
@Table(name = "promotions")
@Getter
@Setter
@NoArgsConstructor
public class Promotion extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 30)
    private PromotionType type;

    /** A share for PERCENTAGE_OFF, an amount for AMOUNT_OFF and BUNDLE. */
    @Column(precision = 19, scale = 4)
    private BigDecimal value;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "buy_quantity", precision = 19, scale = 3)
    private BigDecimal buyQuantity;

    @Column(name = "get_quantity", precision = 19, scale = 3)
    private BigDecimal getQuantity;

    /** Below this the promotion does not apply at all. */
    @Column(name = "min_quantity", precision = 19, scale = 3)
    private BigDecimal minQuantity;

    /** Lower runs first. Ties broken by code. */
    @Column(nullable = false)
    private int priority = 100;

    /** False means it cannot be combined: the single best-value promotion wins instead. */
    @Column(nullable = false)
    private boolean stackable = false;

    @Column(name = "member_only", nullable = false)
    private boolean memberOnly = false;

    /** Null applies to every branch. */
    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(
            mappedBy = "promotion",
            cascade = CascadeType.ALL,
            fetch = FetchType.EAGER,
            orphanRemoval = true)
    private List<PromotionRule> rules = new ArrayList<>();

    public Promotion(String code, String name, PromotionType type) {
        this.code = code;
        this.name = name;
        this.type = type;
    }

    public boolean runsAt(Instant at) {
        if (!active) {
            return false;
        }
        return (validFrom == null || !at.isBefore(validFrom))
                && (validTo == null || at.isBefore(validTo));
    }

    public boolean appliesToBranch(UUID branch) {
        return branchId == null || branchId.equals(branch);
    }

    /** Whether any rule points at this product or its category. */
    public boolean covers(UUID productId, UUID categoryId) {
        return rules.stream().anyMatch(rule -> rule.covers(productId, categoryId));
    }

    public void addRule(PromotionScope scope, UUID scopeId) {
        rules.add(new PromotionRule(this, scope, scopeId));
    }
}
