package com.pos.catalog.domain;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Optional;

import jakarta.persistence.CascadeType;
import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.OneToMany;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A tax treatment, e.g. standard rated or zero rated.
 *
 * <p>Zero rated and exempt both work out to no tax, and are still separate classes, because a VAT
 * return reports them separately and input tax is recoverable on one but not the other. Collapsing
 * them into "rate = 0" loses information the business is legally required to keep.
 */
@Entity
@Table(name = "tax_classes")
@Getter
@Setter
@NoArgsConstructor
public class TaxClass extends BaseEntity {

    @Column(nullable = false, length = 50)
    private String code;

    @Column(nullable = false, length = 150)
    private String name;

    @Column(length = 255)
    private String description;

    @Column(nullable = false)
    private boolean active = true;

    @OneToMany(
            mappedBy = "taxClass",
            cascade = CascadeType.ALL,
            fetch = FetchType.LAZY,
            orphanRemoval = true)
    private List<TaxRate> rates = new ArrayList<>();

    public TaxClass(String code, String name) {
        this.code = code;
        this.name = name;
    }

    /**
     * The rate in force at an instant.
     *
     * <p>Always asked "as at" a time rather than "now", so reprinting last month's receipt uses
     * last month's rate.
     */
    public Optional<TaxRate> rateAt(Instant at) {
        return rates.stream().filter(rate -> rate.appliesAt(at)).findFirst();
    }
}
