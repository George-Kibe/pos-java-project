package com.pos.sales.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** A branch's receipt text: above and below the sale, and how to reach the branch. */
@Entity
@Table(name = "receipt_settings")
@Getter
@Setter
@NoArgsConstructor
public class ReceiptSettings extends BaseEntity {

    @Column(name = "branch_id", nullable = false, updatable = false)
    private UUID branchId;

    /** Lines above the sale, separated by newlines. */
    @Column(length = 500)
    private String header;

    /** Lines below the sale, separated by newlines. */
    @Column(length = 500)
    private String footer;

    @Column(length = 300)
    private String address;

    @Column(length = 30)
    private String phone;

    @Column(name = "tax_pin", length = 30)
    private String taxPin;

    public ReceiptSettings(UUID branchId) {
        this.branchId = branchId;
    }
}
