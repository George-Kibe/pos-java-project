package com.pos.purchasing.domain;

import java.math.BigDecimal;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The one row of expense settings: above this amount, an expense needs a second person. */
@Entity
@Table(name = "expense_settings")
@Getter
@Setter
@NoArgsConstructor
public class ExpenseSettings extends BaseEntity {

    @Column(name = "approval_limit", nullable = false, precision = 19, scale = 4)
    private BigDecimal approvalLimit;

    @Column(nullable = false, length = 3)
    private String currency = "KES";
}
