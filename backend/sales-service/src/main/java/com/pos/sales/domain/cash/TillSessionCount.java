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

/** One denomination of a closing count, beside what the ledger expected. */
@Entity
@Table(name = "till_session_counts")
@Getter
@Setter
@NoArgsConstructor
public class TillSessionCount extends BaseEntity {

    @Column(name = "till_session_id", nullable = false)
    private UUID tillSessionId;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Column(nullable = false, precision = 19, scale = 4)
    private BigDecimal denomination;

    @Column(nullable = false)
    private int counted;

    @Column(nullable = false)
    private int expected;

    public TillSessionCount(
            UUID tillSessionId, UUID branchId, BigDecimal denomination, int counted, int expected) {
        this.tillSessionId = tillSessionId;
        this.branchId = branchId;
        this.denomination = denomination;
        this.counted = counted;
        this.expected = expected;
    }
}
