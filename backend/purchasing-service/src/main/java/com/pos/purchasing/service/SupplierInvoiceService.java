package com.pos.purchasing.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.purchasing.config.PurchasingProperties;
import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.domain.InvoiceMatchStatus;
import com.pos.purchasing.domain.InvoiceVariance;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierInvoice;
import com.pos.purchasing.domain.matching.MatchResult;
import com.pos.purchasing.domain.matching.MatchVerdict;
import com.pos.purchasing.domain.matching.MatchableLine;
import com.pos.purchasing.domain.matching.ThreeWayMatcher;
import com.pos.purchasing.repository.SupplierInvoiceRepository;

import lombok.RequiredArgsConstructor;

/**
 * Supplier invoices and whether to pay them.
 *
 * <p>The match is run against the order and the receipt and its verdict is stored. An exception is
 * never payable by itself: someone either corrects the documents or accepts the difference with a
 * reason, and that acceptance is recorded against the invoice.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierInvoiceService {

    private final SupplierInvoiceRepository invoices;
    private final SupplierService suppliers;
    private final GoodsReceiptService receipts;
    private final PurchaseOrderService orders;
    private final PurchasingProperties properties;

    /** One line as the supplier billed it. */
    public record InvoiceLineRequest(
            UUID productId, String sku, BigDecimal quantity, BigDecimal unitCost) {}

    /** An invoice and what the match made of it. */
    public record MatchedInvoice(SupplierInvoice invoice, MatchResult result) {}

    public Page<SupplierInvoice> list(InvoiceMatchStatus status, Pageable pageable) {
        return status == null
                ? invoices.findAll(pageable)
                : invoices.findByMatchStatus(status, pageable);
    }

    public SupplierInvoice require(UUID id) {
        return invoices.findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Supplier invoice", id));
    }

    /**
     * Records an invoice and matches it in one step.
     *
     * <p>Matched on arrival rather than on request, because an invoice nobody has looked at is
     * indistinguishable from one that matched cleanly - and the whole value of the check is that
     * the exceptions are visible without anyone going looking.
     */
    @Transactional
    public MatchedInvoice record(
            UUID supplierId,
            String invoiceNumber,
            LocalDate invoiceDate,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            UUID purchaseOrderId,
            UUID grnId,
            List<InvoiceLineRequest> lines) {

        Supplier supplier = suppliers.require(supplierId);

        if (invoices.existsBySupplierIdAndInvoiceNumber(supplierId, invoiceNumber)) {
            throw new Errors.ConflictException(
                    "supplier_invoice.duplicate",
                    "Invoice %s from %s has already been recorded"
                            .formatted(invoiceNumber, supplier.getName()));
        }

        SupplierInvoice invoice =
                new SupplierInvoice(invoiceNumber, supplier, invoiceDate, netAmount, taxAmount);

        GoodsReceivedNote grn = null;
        if (grnId != null) {
            grn = receipts.require(grnId);
            if (!grn.isPosted()) {
                throw new Errors.BusinessRuleException(
                        "supplier_invoice.grn_not_posted",
                        "Goods receipt %s has not been posted, so there is nothing to match against"
                                .formatted(grn.getGrnNumber()));
            }
            invoice.setGrn(grn);
            invoice.setBranchId(grn.getBranchId());
        }

        PurchaseOrder order = null;
        if (purchaseOrderId != null) {
            order = orders.require(purchaseOrderId);
            invoice.setPurchaseOrder(order);
            if (invoice.getBranchId() == null) {
                invoice.setBranchId(order.getBranchId());
            }
        } else if (grn != null && grn.getPurchaseOrder() != null) {
            order = grn.getPurchaseOrder();
            invoice.setPurchaseOrder(order);
        }

        MatchResult result = runMatch(order, grn, lines);
        applyVerdict(invoice, result, currentActor());

        return new MatchedInvoice(invoices.save(invoice), result);
    }

    /**
     * Accepts an invoice that failed the match.
     *
     * <p>Requires a reason, and keeps it. A supplier who over-bills by a little every month is only
     * visible if each acceptance was written down.
     */
    @Transactional
    public SupplierInvoice acceptException(UUID id, String reason) {
        SupplierInvoice invoice = require(id);

        if (invoice.getMatchStatus() != InvoiceMatchStatus.EXCEPTION
                && invoice.getMatchStatus() != InvoiceMatchStatus.DISPUTED) {
            throw new Errors.BusinessRuleException(
                    "supplier_invoice.not_an_exception",
                    "Invoice %s is %s; there is nothing to accept"
                            .formatted(invoice.getInvoiceNumber(), invoice.getMatchStatus()));
        }
        if (reason == null || reason.isBlank()) {
            throw new Errors.BusinessRuleException(
                    "supplier_invoice.reason_required",
                    "Accepting a match exception requires a reason");
        }

        invoice.setMatchStatus(InvoiceMatchStatus.APPROVED_FOR_PAYMENT);
        invoice.setOverrideReason(reason);
        invoice.setApprovedForPaymentAt(Instant.now());
        invoice.setApprovedForPaymentBy(currentActor());
        return invoices.save(invoice);
    }

    /** Takes an invoice up with the supplier, keeping it out of payment runs meanwhile. */
    @Transactional
    public SupplierInvoice dispute(UUID id, String reason) {
        SupplierInvoice invoice = require(id);
        invoice.setMatchStatus(InvoiceMatchStatus.DISPUTED);
        invoice.setMatchNotes(reason);
        return invoices.save(invoice);
    }

    /** Clears a cleanly matched invoice for payment. */
    @Transactional
    public SupplierInvoice approveForPayment(UUID id) {
        SupplierInvoice invoice = require(id);

        if (!invoice.getMatchStatus().isPayable()) {
            throw new Errors.BusinessRuleException(
                    "supplier_invoice.not_payable",
                    "Invoice %s is %s and cannot be approved for payment"
                            .formatted(invoice.getInvoiceNumber(), invoice.getMatchStatus()));
        }

        invoice.setMatchStatus(InvoiceMatchStatus.APPROVED_FOR_PAYMENT);
        invoice.setApprovedForPaymentAt(Instant.now());
        invoice.setApprovedForPaymentBy(currentActor());
        return invoices.save(invoice);
    }

    /** Who is doing this, taken from the verified token rather than from the request. */
    private static UUID currentActor() {
        return AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null);
    }

    private MatchResult runMatch(
            PurchaseOrder order, GoodsReceivedNote grn, List<InvoiceLineRequest> lines) {

        List<MatchableLine> orderLines =
                order == null
                        ? List.of()
                        : order.getLines().stream()
                                .map(
                                        line ->
                                                new MatchableLine(
                                                        line.getProductId(),
                                                        line.getSku(),
                                                        line.getQuantityOrdered(),
                                                        line.getUnitCost()))
                                .toList();

        List<MatchableLine> receivedLines =
                grn == null
                        ? List.of()
                        : grn.getLines().stream()
                                .filter(line -> line.quantityAccepted().signum() > 0)
                                .map(
                                        line ->
                                                new MatchableLine(
                                                        line.getProductId(),
                                                        line.getSku(),
                                                        line.quantityAccepted(),
                                                        line.getUnitCost()))
                                .toList();

        List<MatchableLine> invoiceLines =
                lines == null
                        ? List.of()
                        : lines.stream()
                                .map(
                                        line ->
                                                new MatchableLine(
                                                        line.productId(),
                                                        line.sku(),
                                                        line.quantity(),
                                                        line.unitCost()))
                                .toList();

        return ThreeWayMatcher.match(
                orderLines, receivedLines, invoiceLines, properties.tolerance());
    }

    /**
     * Writes the match's conclusion onto the invoice.
     *
     * <p>An invoice with nothing to match against stays PENDING rather than being called MATCHED: a
     * verdict with no evidence behind it is worse than no verdict, because it looks like a check
     * that passed.
     */
    private void applyVerdict(SupplierInvoice invoice, MatchResult result, UUID actorId) {
        if (result.variances().isEmpty()
                && result.justifiedTotal().signum() == 0
                && invoice.getGrn() == null) {
            invoice.setMatchStatus(InvoiceMatchStatus.PENDING);
            invoice.setMatchNotes("No goods receipt to match against");
            return;
        }

        invoice.setMatchStatus(
                switch (result.verdict()) {
                    case MATCHED -> InvoiceMatchStatus.MATCHED;
                    case WITHIN_TOLERANCE -> InvoiceMatchStatus.WITHIN_TOLERANCE;
                    case EXCEPTION -> InvoiceMatchStatus.EXCEPTION;
                });
        invoice.setVarianceAmount(result.variance());
        invoice.setJustifiedTotal(result.justifiedTotal());
        result.variances()
                .forEach(
                        finding ->
                                invoice.getVariances().add(new InvoiceVariance(invoice, finding)));
        invoice.setMatchedAt(Instant.now());
        invoice.setMatchedBy(actorId);
        invoice.setMatchNotes(summarise(result));
    }

    private static String summarise(MatchResult result) {
        if (result.variances().isEmpty()) {
            return "Invoice, receipt and order agree";
        }
        StringBuilder summary = new StringBuilder();
        if (result.verdict() == MatchVerdict.WITHIN_TOLERANCE) {
            summary.append("Within tolerance. ");
        }
        result.variances()
                .forEach(
                        variance ->
                                summary.append(variance.type())
                                        .append(" on ")
                                        .append(variance.sku())
                                        .append(": ")
                                        .append(variance.description())
                                        .append(" (")
                                        .append(variance.amountEffect())
                                        .append("). "));
        String text = summary.toString().trim();
        return text.length() > 1000 ? text.substring(0, 997) + "..." : text;
    }
}
