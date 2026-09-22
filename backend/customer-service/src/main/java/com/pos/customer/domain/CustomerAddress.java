package com.pos.customer.domain;

import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

@Entity
@Table(name = "customer_addresses")
@Getter
@Setter
@NoArgsConstructor
public class CustomerAddress extends BaseEntity {

    @Column(name = "customer_id", nullable = false, updatable = false)
    private UUID customerId;

    @Column(length = 40)
    private String label;

    @Column(nullable = false, length = 160)
    private String line1;

    @Column(length = 160)
    private String line2;

    @Column(length = 80)
    private String town;

    @Column(length = 80)
    private String county;

    @Column(name = "is_default", nullable = false)
    private boolean defaultAddress;

    public CustomerAddress(UUID customerId, String line1) {
        this.customerId = customerId;
        this.line1 = line1;
    }
}
