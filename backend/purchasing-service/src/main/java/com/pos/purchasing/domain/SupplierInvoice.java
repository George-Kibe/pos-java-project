package com.pos.purchasing.domain;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/**
 * A supplier's bill, and the verdict on whether to pay it.
 *
 * <p>The match verdict is stored rather than recomputed on read. It is a decision - sometimes one a
 * person made deliberately, overriding an exception with a reason - and recomputing it would erase
 * that judgement the next time prices or receipts changed.
 */
@Entity
@Table(name = "supplier_invoices")
@Getter
@Setter
@NoArgsConstructor
public class SupplierInvoice extends BaseEntity {

    @Column(name = "invoice_number", nullable = false, length = 50)
    private String invoiceNumber;

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "supplier_id", nullable = false)
    private Supplier supplier;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "purchase_order_id")
    private PurchaseOrder purchaseOrder;

    @ManyToOne(fetch = FetchType.LAZY)
    @JoinColumn(name = "grn_id")
    private GoodsReceivedNote grn;

    @Column(name = "branch_id")
    private UUID branchId;

    @Column(name = "invoice_date", nullable = false)
    private LocalDate invoiceDate;

    @Column(name = "due_date")
    private LocalDate dueDate;

    @Column(name = "net_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal netAmount;

    @Column(name = "tax_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal taxAmount = BigDecimal.ZERO;

    @Column(name = "total_amount", nullable = false, precision = 19, scale = 4)
    private BigDecimal totalAmount;

    @Column(nullable = false, length = 3)
    private String currency = "KES";

    @Enumerated(EnumType.STRING)
    @Column(name = "match_status", nullable = false, length = 25)
    private InvoiceMatchStatus matchStatus = InvoiceMatchStatus.PENDING;

    @Column(name = "variance_amount", precision = 19, scale = 4)
    private BigDecimal varianceAmount;

    /** What the delivery justifies paying, as the match found it. */
    @Column(name = "justified_total", precision = 19, scale = 4)
    private BigDecimal justifiedTotal;

    /**
     * The match's findings, product by product. Loaded with the invoice (a handful per invoice at
     * most), in batches across a page of them.
     */
    @jakarta.persistence.OneToMany(
            mappedBy = "invoice",
            cascade = jakarta.persistence.CascadeType.ALL,
            fetch = FetchType.EAGER)
    @org.hibernate.annotations.BatchSize(size = 50)
    private java.util.List<InvoiceVariance> variances = new java.util.ArrayList<>();

    @Column(name = "match_notes", length = 1000)
    private String matchNotes;

    @Column(name = "matched_at")
    private Instant matchedAt;

    @Column(name = "matched_by")
    private UUID matchedBy;

    @Column(name = "override_reason", length = 500)
    private String overrideReason;

    @Column(name = "approved_for_payment_at")
    private Instant approvedForPaymentAt;

    @Column(name = "approved_for_payment_by")
    private UUID approvedForPaymentBy;

    public SupplierInvoice(
            String invoiceNumber,
            Supplier supplier,
            LocalDate invoiceDate,
            BigDecimal netAmount,
            BigDecimal taxAmount) {

        this.invoiceNumber = invoiceNumber;
        this.supplier = supplier;
        this.invoiceDate = invoiceDate;
        this.netAmount = netAmount;
        this.taxAmount = taxAmount == null ? BigDecimal.ZERO : taxAmount;
        this.totalAmount = this.netAmount.add(this.taxAmount);
        this.currency = supplier.getCurrency();
        this.dueDate = invoiceDate.plusDays(supplier.getPaymentTermsDays());
    }
}
