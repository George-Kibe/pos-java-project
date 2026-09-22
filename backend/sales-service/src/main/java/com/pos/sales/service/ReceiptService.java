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
import com.pos.sales.domain.totals.SaleTotals;
import com.pos.sales.domain.totals.SaleTotalsCalculator;
import com.pos.sales.domain.totals.TaxClassTotal;
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

    /** The breakdown as it will be printed, for a caller that wants it without the document. */
    public List<TaxClassTotal> taxBreakdownOf(Sale sale) {
        return totalsOf(sale).taxBreakdown();
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
