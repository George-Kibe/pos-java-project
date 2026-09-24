package com.pos.notification.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.text.DecimalFormat;
import java.text.DecimalFormatSymbols;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.HashMap;
import java.util.List;
import java.util.Locale;
import java.util.Map;

import com.pos.events.sales.ReceiptEmailRequestedPayload;

/**
 * Turns a receipt event into what the template prints.
 *
 * <p>Money is carried to four places and rounded here, {@code HALF_UP} to two, because this is the
 * display step. The time is shown in the shop's zone: the customer reads it against their own day.
 */
public final class ReceiptEmailModel {

    private static final DateTimeFormatter WHEN =
            DateTimeFormatter.ofPattern("d MMM yyyy, HH:mm", Locale.ENGLISH);

    private ReceiptEmailModel() {}

    /** One printed line. Every figure is already a string, so the templates only lay it out. */
    public record Line(
            String name, String quantity, String unitPrice, String discount, String total) {}

    public record TaxLine(String taxClass, String rate, String taxable, String tax) {}

    public record Tender(String method, String amount) {}

    public static Map<String, Object> from(ReceiptEmailRequestedPayload receipt, ZoneId zone) {
        String currency = receipt.currency();
        Map<String, Object> model = new HashMap<>();
        model.put("receiptNumber", receipt.receiptNumber());
        model.put(
                "issuedAt",
                receipt.completedAt() == null
                        ? ""
                        : WHEN.format(receipt.completedAt().atZone(zone)));
        model.put("currency", currency);
        model.put(
                "lines",
                receipt.lines().stream()
                        .map(
                                line ->
                                        new Line(
                                                line.productName(),
                                                quantity(line.quantity()),
                                                money(line.unitPrice()),
                                                isZero(line.discount())
                                                        ? null
                                                        : money(line.discount()),
                                                money(line.lineTotal())))
                        .toList());
        model.put(
                "taxLines",
                receipt.taxBreakdown().stream()
                        .map(
                                tax ->
                                        new TaxLine(
                                                tax.taxClassCode(),
                                                percent(tax.rate()),
                                                money(tax.taxable()),
                                                money(tax.tax())))
                        .toList());
        model.put(
                "tenders",
                receipt.payments().stream()
                        .map(tender -> new Tender(label(tender.method()), money(tender.amount())))
                        .toList());
        model.put(
                "discountTotal",
                isZero(receipt.discountTotal()) ? null : money(receipt.discountTotal()));
        model.put("taxTotal", money(receipt.taxTotal()));
        model.put("grandTotal", money(receipt.grandTotal()));
        model.put(
                "amountTendered",
                receipt.amountTendered() == null ? null : money(receipt.amountTendered()));
        model.put(
                "changeGiven",
                receipt.changeGiven() == null || isZero(receipt.changeGiven())
                        ? null
                        : money(receipt.changeGiven()));
        return model;
    }

    static String money(BigDecimal amount) {
        if (amount == null) {
            return "0.00";
        }
        DecimalFormat format =
                new DecimalFormat("#,##0.00", DecimalFormatSymbols.getInstance(Locale.ENGLISH));
        return format.format(amount.setScale(2, RoundingMode.HALF_UP));
    }

    /** Whole units print whole; weighed goods keep their grams. */
    static String quantity(BigDecimal quantity) {
        if (quantity == null) {
            return "";
        }
        BigDecimal stripped = quantity.stripTrailingZeros();
        return (stripped.scale() < 0 ? stripped.setScale(0) : stripped).toPlainString();
    }

    /** Rates are stored as fractions: 0.16 prints as 16%. */
    static String percent(BigDecimal rate) {
        if (rate == null) {
            return "0%";
        }
        return quantity(rate.movePointRight(2)) + "%";
    }

    private static String label(com.pos.events.payments.PaymentMethod method) {
        return switch (method) {
            case CASH -> "Cash";
            case CARD -> "Card";
            case MPESA -> "M-Pesa";
            case LOYALTY -> "Loyalty points";
            case VOUCHER -> "Voucher";
            case ACCOUNT -> "On account";
        };
    }

    private static boolean isZero(BigDecimal value) {
        return value == null || value.signum() == 0;
    }

    /** Obviously fake figures, for the template preview. */
    public static ReceiptEmailRequestedPayload sample() {
        return new ReceiptEmailRequestedPayload(
                java.util.UUID.randomUUID(),
                java.util.UUID.randomUUID(),
                "R-000000",
                java.util.UUID.randomUUID(),
                "someone@example.com",
                "Ada Lovelace",
                java.time.Instant.parse("2026-01-02T09:15:00Z"),
                "KES",
                List.of(
                        new ReceiptEmailRequestedPayload.Line(
                                "Sample milk 500ml",
                                BigDecimal.valueOf(2),
                                new BigDecimal("65.0000"),
                                BigDecimal.ZERO,
                                new BigDecimal("130.0000")),
                        new ReceiptEmailRequestedPayload.Line(
                                "Sample bananas (kg)",
                                new BigDecimal("0.735"),
                                new BigDecimal("120.0000"),
                                BigDecimal.ZERO,
                                new BigDecimal("88.2000"))),
                List.of(
                        new ReceiptEmailRequestedPayload.TaxLine(
                                "VAT_STANDARD",
                                new BigDecimal("0.16"),
                                new BigDecimal("112.0690"),
                                new BigDecimal("17.9310")),
                        new ReceiptEmailRequestedPayload.TaxLine(
                                "ZERO_RATED",
                                BigDecimal.ZERO,
                                new BigDecimal("88.2000"),
                                BigDecimal.ZERO)),
                List.of(
                        new ReceiptEmailRequestedPayload.Tender(
                                com.pos.events.payments.PaymentMethod.CASH,
                                new BigDecimal("218.2000"))),
                BigDecimal.ZERO,
                new BigDecimal("17.9310"),
                new BigDecimal("218.2000"),
                new BigDecimal("300.0000"),
                new BigDecimal("81.80"));
    }
}
