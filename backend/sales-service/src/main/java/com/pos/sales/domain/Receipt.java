package com.pos.sales.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import org.hibernate.annotations.JdbcTypeCode;
import org.hibernate.type.SqlTypes;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * The document the customer was handed.
 *
 * <p>The tax breakdown is stored per class rather than derived on read, so a reprint is identical
 * to the original even if a rate changed since - and so a VAT return is built from what was
 * actually charged.
 */
@Entity
@Table(name = "receipts")
@Getter
@Setter
@NoArgsConstructor
public class Receipt extends BaseEntity {

    public enum Type {
        SALE,
        RETURN,
        REPRINT
    }

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "sale_id", nullable = false)
    private Sale sale;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "return_id")
    private SaleReturn saleReturn;

    @Column(name = "receipt_number", nullable = false, length = 30)
    private String receiptNumber;

    @Column(name = "branch_id", nullable = false)
    private UUID branchId;

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 20)
    private Type type = Type.SALE;

    @Column(name = "issued_at", nullable = false)
    private Instant issuedAt = Instant.now();

    /** [{taxClassCode, taxRate, net, tax, gross}], summing to the sale's totals. */
    @JdbcTypeCode(SqlTypes.JSON)
    @Column(name = "tax_breakdown", columnDefinition = "jsonb", nullable = false)
    private String taxBreakdown;

    @Column(name = "net_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal netTotal;

    @Column(name = "tax_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxTotal;

    @Column(name = "grand_total", nullable = false, precision = 19, scale = 4)
    private BigDecimal grandTotal;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Column(name = "print_count", nullable = false)
    private int printCount;

    @Column(name = "last_printed_at")
    private Instant lastPrintedAt;

    public Receipt(Sale sale, String receiptNumber, Type type, String taxBreakdown) {
        this.sale = sale;
        this.receiptNumber = receiptNumber;
        this.branchId = sale.getBranchId();
        this.type = type;
        this.taxBreakdown = taxBreakdown;
        this.netTotal = sale.getNetTotal();
        this.taxTotal = sale.getTaxTotal();
        this.grandTotal = sale.getGrandTotal();
        this.currency = sale.getCurrency();
    }

    /** Counted, because a receipt printed six times is a question worth being able to answer. */
    public void recordPrint() {
        this.printCount = printCount + 1;
        this.lastPrintedAt = Instant.now();
    }
}
