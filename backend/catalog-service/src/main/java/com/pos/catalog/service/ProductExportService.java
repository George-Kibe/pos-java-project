package com.pos.catalog.service;

import java.io.ByteArrayOutputStream;
import java.io.IOException;
import java.io.OutputStreamWriter;
import java.io.UncheckedIOException;
import java.nio.charset.StandardCharsets;
import java.util.Comparator;
import java.util.List;
import java.util.stream.Collectors;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVPrinter;
import org.springframework.data.domain.PageRequest;
import org.springframework.data.domain.Pageable;
import org.springframework.data.domain.Sort;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.ProductBarcode;
import com.pos.catalog.repository.ProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * The catalogue as CSV, in exactly the columns {@link ProductImportService} reads - so a file can
 * be exported, corrected in a spreadsheet and loaded back.
 */
@Service
@RequiredArgsConstructor
public class ProductExportService {

    /** The import's columns, in the import's order. */
    public static final List<String> COLUMNS =
            List.of(
                    "sku",
                    "name",
                    "categoryCode",
                    "uomCode",
                    "taxClassCode",
                    "basePrice",
                    "brandCode",
                    "sellByWeight",
                    "priceIncludesTax",
                    "barcodes");

    private static final int BATCH = 500;

    private final ProductRepository products;

    @Transactional(readOnly = true)
    public byte[] csv() {
        ByteArrayOutputStream bytes = new ByteArrayOutputStream();
        // A BOM, so Excel on Windows opens the file as UTF-8; the import skips it.
        bytes.writeBytes(new byte[] {(byte) 0xEF, (byte) 0xBB, (byte) 0xBF});
        try (CSVPrinter printer =
                new CSVPrinter(
                        new OutputStreamWriter(bytes, StandardCharsets.UTF_8),
                        CSVFormat.DEFAULT
                                .builder()
                                .setHeader(COLUMNS.toArray(String[]::new))
                                .get())) {
            Pageable page = PageRequest.of(0, BATCH, Sort.by("sku"));
            while (true) {
                var batch = products.findAll(page);
                for (Product product : batch) {
                    printer.printRecord(
                            row(product).stream().map(ProductExportService::guarded).toList());
                }
                if (!batch.hasNext()) {
                    break;
                }
                page = batch.nextPageable();
            }
        } catch (IOException e) {
            throw new UncheckedIOException("Could not write CSV", e);
        }
        return bytes.toByteArray();
    }

    private static List<String> row(Product product) {
        String barcodes =
                product.getBarcodes().stream()
                        // The primary first: the import makes the first one primary.
                        .sorted(Comparator.comparing(ProductBarcode::isPrimary).reversed())
                        .map(ProductBarcode::getBarcode)
                        .collect(Collectors.joining("|"));
        return List.of(
                product.getSku(),
                product.getName(),
                product.getCategory().getCode(),
                product.getUnitOfMeasure().getCode(),
                product.getTaxClass().getCode(),
                product.getBasePrice().toPlainString(),
                product.getBrand() == null ? "" : product.getBrand().getCode(),
                product.isSellByWeight() ? "yes" : "no",
                product.isPriceIncludesTax() ? "yes" : "no",
                barcodes);
    }

    /**
     * A cell a spreadsheet would run as a formula ({@code =}, {@code +}, {@code -}, {@code @})
     * leaves with an apostrophe in front, which spreadsheets read as "this is text".
     */
    static String guarded(String cell) {
        return startsLikeAFormula(cell, 0) ? "'" + cell : cell;
    }

    /** What {@link #guarded} added, taken off again on the way back in. */
    static String unguarded(String cell) {
        return cell != null && cell.startsWith("'") && startsLikeAFormula(cell, 1)
                ? cell.substring(1)
                : cell;
    }

    private static boolean startsLikeAFormula(String cell, int at) {
        return cell != null && cell.length() > at && "=+-@".indexOf(cell.charAt(at)) >= 0;
    }
}
