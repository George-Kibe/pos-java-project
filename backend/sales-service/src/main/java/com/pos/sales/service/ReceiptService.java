package com.pos.sales.service;

import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.events.EventJson;
import com.pos.sales.domain.Receipt;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleStatus;
import com.pos.sales.domain.totals.SaleTotals;
import com.pos.sales.domain.totals.SaleTotalsCalculator;
import com.pos.sales.domain.totals.TaxClassTotal;
import com.pos.sales.messaging.SalesEventPublisher;
import com.pos.sales.repository.ReceiptRepository;

import lombok.RequiredArgsConstructor;

/**
 * The document the customer is handed.
 *
 * <p>The tax breakdown is computed once, at issue, and stored. A reprint then shows what was
 * actually charged rather than what the current rates would produce - which is the difference
 * between a reprint and a recalculation, and only one of them is a receipt.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReceiptService {

    private final ReceiptRepository receipts;
    private final SalesEventPublisher events;
    private final ReceiptSettingsService receiptSettings;

    public Receipt require(UUID id) {
        return receipts.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Receipt", id));
    }

    public List<Receipt> forSale(UUID saleId) {
        return receipts.findBySaleId(saleId);
    }

    /** Issues the receipt for a sale that has just been paid. */
    @Transactional(propagation = Propagation.MANDATORY)
    public Receipt issueFor(Sale sale) {
        SaleTotals totals = totalsOf(sale);
        Receipt receipt =
                new Receipt(
                        sale,
                        sale.getReceiptNumber(),
                        Receipt.Type.SALE,
                        EventJson.write(totals.taxBreakdown()));
        return receipts.save(receipt);
    }

    /**
     * Records that a receipt was printed again.
     *
     * <p>Counted rather than ignored, because a receipt printed six times is a question worth being
     * able to answer - and a reprint is the oldest way to walk out with goods twice.
     */
    @Transactional
    public Receipt recordReprint(UUID receiptId) {
        Receipt receipt = require(receiptId);
        receipt.recordPrint();
        return receipts.save(receipt);
    }

    /**
     * Sends a copy of a paid sale's receipt by email.
     *
     * <p>Only the sale receipt of a sale that still stands: a voided sale's receipt is no longer a
     * record of anything the customer owes or owns.
     */
    @Transactional
    public Receipt emailReceipt(UUID saleId, String email, String recipientName) {
        Receipt receipt =
                receipts.findBySaleId(saleId).stream()
                        .filter(candidate -> candidate.getType() == Receipt.Type.SALE)
                        .findFirst()
                        .orElseThrow(
                                () ->
                                        new Errors.ConflictException(
                                                "receipt.not_issued",
                                                "This sale has no receipt yet: it is not paid."));
        if (receipt.getSale().getStatus() != SaleStatus.PAID) {
            throw new Errors.ConflictException(
                    "receipt.sale_not_standing",
                    "This sale is %s; its receipt cannot be sent."
                            .formatted(receipt.getSale().getStatus()));
        }
        List<TaxClassTotal> breakdown =
                List.of(EventJson.read(receipt.getTaxBreakdown(), TaxClassTotal[].class));
        var text = receiptSettings.of(receipt.getSale().getBranchId());
        events.receiptEmailRequested(
                receipt,
                breakdown,
                email.trim(),
                blankToNull(recipientName),
                new com.pos.events.sales.ReceiptEmailRequestedPayload.ReceiptText(
                        text.getHeader(),
                        text.getFooter(),
                        text.getAddress(),
                        text.getPhone(),
                        text.getTaxPin()));
        return receipt;
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.trim();
    }

    /**
     * Totals a sale from its snapshotted lines.
     *
     * <p>From the sale's own lines rather than the cart's, and never by calling catalog again: the
     * figures on those lines are what the customer paid, and a receipt that disagrees with them is
     * worse than no receipt.
     */
    private static SaleTotals totalsOf(Sale sale) {
        return SaleTotalsCalculator.total(
                sale.getLines().stream()
                        .map(
                                line ->
                                        new com.pos.sales.domain.totals.PricedLine(
                                                line.getProductId(),
                                                line.getSku(),
                                                line.getQuantity(),
                                                line.getTaxClassCode(),
                                                line.getTaxRate(),
                                                line.getNetAmount(),
                                                line.getTaxAmount(),
                                                line.getDiscountTotal(),
                                                line.getLineTotal()))
                        .toList());
    }
}
