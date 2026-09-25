package com.pos.purchasing.domain;

import java.math.BigDecimal;
import java.util.UUID;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.FetchType;
import jakarta.persistence.JoinColumn;
import jakarta.persistence.ManyToOne;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;
import com.pos.purchasing.domain.matching.LineVariance;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** One finding of an invoice's three-way match, kept with the invoice. */
@Entity
@Table(name = "supplier_invoice_variances")
@Getter
@Setter
@NoArgsConstructor
public class InvoiceVariance extends BaseEntity {

    @ManyToOne(fetch = FetchType.LAZY, optional = false)
    @JoinColumn(name = "invoice_id", nullable = false)
    private SupplierInvoice invoice;

    @Column(name = "product_id")
    private UUID productId;

    @Column(length = 50)
    private String sku;

    @Column(nullable = false, length = 30)
    private String type;

    @Column(precision = 19, scale = 4)
    private BigDecimal expected;

    @Column(precision = 19, scale = 4)
    private BigDecimal actual;

    @Column(precision = 19, scale = 4)
    private BigDecimal difference;

    @Column(name = "amount_effect", precision = 19, scale = 4)
    private BigDecimal amountEffect;

    @Column(length = 500)
    private String description;

    public InvoiceVariance(SupplierInvoice invoice, LineVariance finding) {
        this.invoice = invoice;
        this.productId = finding.productId();
        this.sku = finding.sku();
        this.type = finding.type().name();
        this.expected = finding.expected();
        this.actual = finding.actual();
        this.difference = finding.difference();
        this.amountEffect = finding.amountEffect();
        String text = finding.description();
        this.description =
                text != null && text.length() > 500 ? text.substring(0, 497) + "..." : text;
    }
}
