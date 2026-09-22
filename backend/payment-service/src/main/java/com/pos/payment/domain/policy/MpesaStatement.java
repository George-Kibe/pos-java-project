package com.pos.payment.domain.policy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDateTime;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.time.format.DateTimeParseException;
import java.util.ArrayList;
import java.util.List;
import java.util.Locale;

/**
 * Reads the statement the M-Pesa organisation portal exports.
 *
 * <p>Daraja has no statement API, so reconciliation starts from this file. The export is forgiving
 * to a person and awkward to a program: a preamble (account name, period) above the header, money
 * written {@code "1,053.00"}, withdrawals and charges mixed in with receipts, and a status column
 * that includes failures. Only completed money <em>in</em> is returned.
 *
 * <p>Pure: no Spring, no JPA.
 */
public final class MpesaStatement {

    public static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");

    private static final List<DateTimeFormatter> TIMES =
            List.of(
                    DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm:ss"),
                    DateTimeFormatter.ofPattern("dd-MM-yyyy HH:mm:ss"),
                    DateTimeFormatter.ofPattern("dd/MM/yyyy HH:mm:ss"),
                    DateTimeFormatter.ofPattern("dd.MM.yyyy HH:mm:ss"));

    private MpesaStatement() {}

    /** One receipt of money. */
    public record Line(
            String receiptNumber, Instant completedAt, BigDecimal paidIn, String details) {}

    /**
     * @throws IllegalArgumentException when there is no header with a receipt and a paid-in column
     *     - the wrong file, which must be said rather than reconciled as empty
     */
    public static List<Line> parse(String csv) {
        List<List<String>> rows = new ArrayList<>();
        for (String raw : csv.split("\\r?\\n")) {
            if (!raw.isBlank()) {
                rows.add(split(raw));
            }
        }

        int header = -1;
        for (int i = 0; i < rows.size(); i++) {
            if (indexOf(rows.get(i), "receipt no") >= 0 && indexOf(rows.get(i), "paid in") >= 0) {
                header = i;
                break;
            }
        }
        if (header < 0) {
            throw new IllegalArgumentException(
                    "Not an M-Pesa statement: no header with 'Receipt No.' and 'Paid In'");
        }

        List<String> columns = rows.get(header);
        int receipt = indexOf(columns, "receipt no");
        int paidIn = indexOf(columns, "paid in");
        int completion = indexOf(columns, "completion time");
        int status = indexOf(columns, "transaction status");
        int details = indexOf(columns, "details");

        List<Line> lines = new ArrayList<>();
        for (List<String> row : rows.subList(header + 1, rows.size())) {
            String number = cell(row, receipt);
            BigDecimal amount = money(cell(row, paidIn));
            if (number.isBlank() || amount == null || amount.signum() <= 0) {
                continue; // a withdrawal, a charge or a trailing total
            }
            if (status >= 0 && !cell(row, status).equalsIgnoreCase("completed")) {
                continue;
            }
            lines.add(
                    new Line(
                            number.trim(),
                            completion >= 0 ? time(cell(row, completion)) : null,
                            amount,
                            details >= 0 ? cell(row, details) : null));
        }
        return List.copyOf(lines);
    }

    private static List<String> split(String line) {
        List<String> cells = new ArrayList<>();
        StringBuilder current = new StringBuilder();
        boolean quoted = false;
        for (int i = 0; i < line.length(); i++) {
            char c = line.charAt(i);
            if (c == '"') {
                if (quoted && i + 1 < line.length() && line.charAt(i + 1) == '"') {
                    current.append('"');
                    i++;
                } else {
                    quoted = !quoted;
                }
            } else if (c == ',' && !quoted) {
                cells.add(current.toString().trim());
                current.setLength(0);
            } else {
                current.append(c);
            }
        }
        cells.add(current.toString().trim());
        return cells;
    }

    private static int indexOf(List<String> row, String name) {
        for (int i = 0; i < row.size(); i++) {
            if (row.get(i).toLowerCase(Locale.ROOT).replace(".", "").trim().startsWith(name)) {
                return i;
            }
        }
        return -1;
    }

    private static String cell(List<String> row, int index) {
        return index >= 0 && index < row.size() ? row.get(index) : "";
    }

    private static BigDecimal money(String text) {
        String cleaned = text.replace(",", "").replace("KES", "").replace("Ksh", "").trim();
        if (cleaned.isEmpty()) {
            return null;
        }
        try {
            return new BigDecimal(cleaned);
        } catch (NumberFormatException e) {
            return null;
        }
    }

    private static Instant time(String text) {
        for (DateTimeFormatter format : TIMES) {
            try {
                return LocalDateTime.parse(text.trim(), format).atZone(NAIROBI).toInstant();
            } catch (DateTimeParseException ignored) {
                // try the next shape
            }
        }
        return null;
    }
}
