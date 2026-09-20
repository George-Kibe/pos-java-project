package com.pos.catalog.domain;

import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * Branch-specific pricing.
 *
 * <p>A missing entry is not an error: the product's base price is always a valid answer, so a price
 * list only needs rows where the branch genuinely differs from the chain.
 */
@Entity
@Table(name = "price_lists")
@Getter
@Setter
@NoArgsConstructor
public class PriceList extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    /** Null applies to every branch. */
    @Column(name = "branch_id")
    private UUID branchId;

    /** Higher wins; ties broken by code so resolution is deterministic. */
    @Column(nullable = false)
    private int priority = 0;

    @Column(name = "valid_from")
    private Instant validFrom;

    @Column(name = "valid_to")
    private Instant validTo;

    @Column(nullable = false)
    private boolean active = true;

    public PriceList(String code, String name, UUID branchId, int priority) {
        this.code = code;
        this.name = name;
        this.branchId = branchId;
        this.priority = priority;
    }

    public boolean appliesAt(Instant at) {
        if (!active) {
            return false;
        }
        return (validFrom == null || !at.isBefore(validFrom))
                && (validTo == null || at.isBefore(validTo));
    }

    public boolean appliesToBranch(UUID branch) {
        return branchId == null || branchId.equals(branch);
    }
}
