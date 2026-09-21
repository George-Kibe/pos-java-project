package com.pos.purchasing.domain;

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
 * Someone we buy from.
 *
 * <p>{@code leadTimeDays} and {@code paymentTermsDays} are not decoration: the first decides when a
 * reorder has to be raised to avoid an empty shelf, the second when an invoice falls due. Both are
 * per supplier because they differ wildly between a local bakery and an importer.
 */
@Entity
@Table(name = "suppliers")
@Getter
@Setter
@NoArgsConstructor
public class Supplier extends BaseEntity {

    @Column(nullable = false, length = 30)
    private String code;

    @Column(nullable = false, length = 200)
    private String name;

    @Column(name = "contact_name", length = 200)
    private String contactName;

    @Column(length = 320)
    private String email;

    @Column(length = 30)
    private String phone;

    @Column(length = 500)
    private String address;

    @Column(name = "tax_identifier", length = 50)
    private String taxIdentifier;

    @Column(name = "payment_terms_days", nullable = false)
    private int paymentTermsDays = 30;

    @Column(name = "lead_time_days", nullable = false)
    private int leadTimeDays = 7;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private SupplierStatus status = SupplierStatus.ACTIVE;

    @Column(length = 1000)
    private String notes;

    public Supplier(String code, String name) {
        this.code = code;
        this.name = name;
    }
}
