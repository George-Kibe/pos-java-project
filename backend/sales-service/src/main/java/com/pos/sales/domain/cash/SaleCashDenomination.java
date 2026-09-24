package com.pos.sales.domain.cash;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** For one cash sale, one denomination: how many the customer handed over, how many went back. */
@Entity
@Table(name = "sale_cash_denominations")
@Getter
@Setter
@NoArgsConstructor
public class SaleCashDenomination extends BaseEntity {

    @Column(name = "sale_id", nullable = false)
    private UUID saleId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal denomination;

    @Column(nullable = false)
    private int received;

    @Column(name = "change_given", nullable = false)
    private int changeGiven;

    public SaleCashDenomination(
            UUID saleId, UUID branchId, BigDecimal denomination, int received, int changeGiven) {
        this.saleId = saleId;
        this.branchId = branchId;
        this.denomination = denomination;
        this.received = received;
        this.changeGiven = changeGiven;
    }
}
