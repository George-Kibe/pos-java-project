package com.pos.events.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import com.pos.events.payments.PaymentMethod;

/**
 * A customer asked for their receipt by email.
 *
 * <p>Carries the receipt as it was issued - the lines, the tax breakdown stored at issue and the
 * tenders - so notification-service renders it without asking sales anything, and a copy sent a
 * week later still shows what was charged.
 *
 * <p>The address travels on the event and nowhere else: sales does not keep it.
 *
 * @param recipientName optional; the greeting falls back to none
 * @param changeGiven cash handed back, already rounded to cents; null when no cash was tendered
 */
public record ReceiptEmailRequestedPayload(
        UUID saleId,
        UUID receiptId,
        String receiptNumber,
        UUID branchId,
        String email,
        String recipientName,
        Instant completedAt,
        String currency,
        List<Line> lines,
        List<TaxLine> taxBreakdown,
        List<Tender> payments,
        BigDecimal discountTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        BigDecimal amountTendered,
        BigDecimal changeGiven) {

    /** One line as charged. Quantity is fractional for weighed goods. */
    public record Line(
            String productName,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal discount,
            BigDecimal lineTotal) {}

    /** Tax per class, as stored on the receipt at issue. */
    public record TaxLine(
            String taxClassCode, BigDecimal rate, BigDecimal taxable, BigDecimal tax) {}

    public record Tender(PaymentMethod method, BigDecimal amount) {}
}
