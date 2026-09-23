package com.pos.reporting.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.openpdf.text.Document;
import org.openpdf.text.DocumentException;
import org.openpdf.text.Element;
import org.openpdf.text.Font;
import org.openpdf.text.FontFactory;
import org.openpdf.text.PageSize;
import org.openpdf.text.Paragraph;
import org.openpdf.text.Phrase;
import org.openpdf.text.pdf.PdfPCell;
import org.openpdf.text.pdf.PdfPTable;
import org.openpdf.text.pdf.PdfWriter;
import org.springframework.stereotype.Service;

/**
 * Reports as files: CSV for a spreadsheet, PDF for a manager's desk.
 *
 * <p>Both take the same table, so what is exported is exactly what the screen shows. Money is
 * rounded to cents here and only here - this is the display step CLAUDE.md allows rounding at.
 */
@Service
public class ExportService {

    /** A quantity: three places, because weighed goods are fractional and are not money. */
    public record Qty(BigDecimal value) {}

    /**
     * A report laid out as a table. Cells are already text, except money, which is formatted here.
     */
    public record Table(
            String title, List<String> subtitle, List<String> headers, List<List<Object>> rows) {}

    public byte[] csv(Table table) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // A BOM, so Excel on Windows opens the file as UTF-8 and "Kĩambu" survives.
        bytes.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (CSVPrinter printer =
                new CSVPrinter(
                        new OutputStreamWriter(bytes, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader(table.headers().toArray(String[]::new))
                                .get())) {
            for (List<Object> row : table.rows()) {
                printer.printRecord(row.stream().map(ExportService::cell).toList());
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write CSV", e);
        }
        return bytes.toByteArray();
    }

    public byte[] pdf(Table table) {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        Document document =
                new Document(
                        table.headers().size() > 6 ? PageSize.A4.rotate() : PageSize.A4,
                        36,
                        36,
                        36,
                        36);
        try {
            PdfWriter.getInstance(document, bytes);
            document.open();
            document.add(
                    new Paragraph(
                            table.title(), FontFactory.getFont(FontFactory.HELVETICA_BOLD, 14)));
            Font small = FontFactory.getFont(FontFactory.HELVETICA, 9);
            for (String line : table.subtitle()) {
                document.add(new Paragraph(line, small));
            }
            document.add(new Paragraph(" "));

            PdfPTable grid = new PdfPTable(table.headers().size());
            grid.setWidthPercentage(100);
            grid.setHeaderRows(1);
            Font header = FontFactory.getFont(FontFactory.HELVETICA_BOLD, 8);
            Font body = FontFactory.getFont(FontFactory.HELVETICA, 8);
            for (String heading : table.headers()) {
                grid.addCell(new PdfPCell(new Phrase(heading, header)));
            }
            for (List<Object> row : table.rows()) {
                for (Object value : row) {
                    PdfPCell cell = new PdfPCell(new Phrase(cell(value), body));
                    if (value instanceof Number || value instanceof Qty) {
                        cell.setHorizontalAlignment(Element.ALIGN_RIGHT);
                    }
                    grid.addCell(cell);
                }
            }
            document.add(grid);
        } catch (DocumentException e) {
            throw new IllegalStateException("Could not write PDF", e);
        } finally {
            document.close();
        }
        return bytes.toByteArray();
    }

    /** Money to cents for display; everything else as text; nothing as an empty cell. */
    static String cell(Object value) {
        if (value == null) {
            return "";
        }
        if (value instanceof Qty qty) {
            return qty.value() == null
                    ? ""
                    : qty.value().setScale(3, RoundingMode.HALF_UP).toPlainString();
        }
        if (value instanceof BigDecimal amount) {
            return amount.setScale(2, RoundingMode.HALF_UP).toPlainString();
        }
        return String.valueOf(value);
    }
}
