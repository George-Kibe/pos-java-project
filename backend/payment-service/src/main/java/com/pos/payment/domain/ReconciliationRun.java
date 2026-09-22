package com.pos.payment.domain;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One M-Pesa statement reconciled against what we recorded. */
@Entity
@Table(name = "reconciliation_runs")
@Getter
@Setter
@NoArgsConstructor
public class ReconciliationRun extends BaseEntity {

    @Column(name = "statement_date", nullable = false)
    private LocalDate statementDate;

    @Column(name = "source_filename", length = 255)
    private String sourceFilename;

    @Column(name = "statement_lines", nullable = false)
    private int statementLines;

    @Column(nullable = false)
    private int matched;

    @Column(name = "amount_mismatches", nullable = false)
    private int amountMismatches;

    @Column(name = "statement_only", nullable = false)
    private int statementOnly;

    @Column(name = "recorded_only", nullable = false)
    private int recordedOnly;

    @Column(name = "statement_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal statementTotal = BigDecimal.ZERO;

    @Column(name = "recorded_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal recordedTotal = BigDecimal.ZERO;

    @Column(name = "variance_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal varianceTotal = BigDecimal.ZERO;

    @Column(name = "run_by")
    private UUID runBy;

    public boolean isClean() {
        return amountMismatches == 0 && statementOnly == 0 && recordedOnly == 0;
    }
}
