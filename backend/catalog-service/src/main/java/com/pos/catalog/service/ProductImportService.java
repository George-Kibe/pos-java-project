package com.pos.catalog.service;

import java.io.BufferedReader;
import java.io.IOException;
import java.io.InputStream;
import java.io.InputStreamReader;
import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;

import org.apache.commons.csv.CSVFormat;
import org.apache.commons.csv.CSVRecord;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Product;
import com.pos.catalog.repository.BrandRepository;
import com.pos.catalog.repository.CategoryRepository;
import com.pos.catalog.repository.ProductRepository;
import com.pos.catalog.repository.TaxClassRepository;
import com.pos.catalog.repository.UnitOfMeasureRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * Bulk product load from CSV.
 *
 * <p>Row by row, each in its own transaction, and a report at the end. Loading a thousand products
 * as one transaction means a single bad row - a typo in a tax class, a price with a stray comma -
 * rejects the other nine hundred and ninety-nine, and whoever prepared the file has to find the
 * problem with no help. Here the good rows land, the bad ones come back with a line number and a
 * reason, and the file can be corrected and re-run: re-importing a row that already exists updates
 * it rather than failing.
 */
@Service
@RequiredArgsConstructor
public class ProductImportService {

    private static final Logger log = LoggerFactory.getLogger(ProductImportService.class);

    private final ProductImportRowWriter rowWriter;

    /** One row's outcome. */
    public record RowError(long line, String sku, String message) {}

    public record ImportReport(int created, int updated, int failed, List<RowError> errors) {

        public int total() {
            return created + updated + failed;
        }
    }

    public ImportReport importFrom(InputStream csv) {
        int created = 0;
        int updated = 0;
        List<RowError> errors = new ArrayList<>();

        CSVFormat format =
                CSVFormat.DEFAULT
                        .builder()
                        .setHeader()
                        .setSkipHeaderRecord(true)
                        .setIgnoreSurroundingSpaces(true)
                        .setIgnoreEmptyLines(true)
                        .get();

        try (BufferedReader reader =
                        new BufferedReader(new InputStreamReader(csv, StandardCharsets.UTF_8));
                var parser = format.parse(reader)) {

            for (CSVRecord record : parser) {
                String sku = get(record, "sku");
                try {
                    boolean existed = rowWriter.upsert(record);
                    if (existed) {
                        updated++;
                    } else {
                        created++;
                    }
                } catch (RuntimeException e) {
                    // One bad row must not take the file down with it.
                    errors.add(new RowError(record.getRecordNumber(), sku, rootMessage(e)));
                }
            }

        } catch (IOException e) {
            throw new Errors.BadRequestException(
                    "import.unreadable", "The file could not be read as CSV: " + e.getMessage());
        }

        log.info(
                "Product import finished: {} created, {} updated, {} failed",
                created,
                updated,
                errors.size());
        return new ImportReport(created, updated, errors.size(), errors);
    }

    static String get(CSVRecord record, String column) {
        return record.isMapped(column) ? record.get(column) : null;
    }

    private static String rootMessage(RuntimeException e) {
        Throwable cause = e;
        while (cause.getCause() != null && cause.getCause() != cause) {
            cause = cause.getCause();
        }
        return cause.getMessage() == null ? e.toString() : cause.getMessage();
    }

    /**
     * Writes one row in its own transaction.
     *
     * <p>A separate bean because {@code REQUIRES_NEW} on a self-invocation does not go through the
     * proxy, and without it a failed row would roll back the rows before it.
     */
    @Service
    @RequiredArgsConstructor
    static class ProductImportRowWriter {

        private final ProductRepository products;
        private final CategoryRepository categories;
        private final BrandRepository brands;
        private final UnitOfMeasureRepository unitsOfMeasure;
        private final TaxClassRepository taxClasses;

        /**
         * @return true if an existing product was updated
         */
        @Transactional(propagation = Propagation.REQUIRES_NEW)
        boolean upsert(CSVRecord record) {
            String sku = require(record, "sku").toUpperCase(java.util.Locale.ROOT);
            String name = require(record, "name");

            var category =
                    categories
                            .findByCode(
                                    require(record, "categoryCode")
                                            .toUpperCase(java.util.Locale.ROOT))
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown category: "
                                                            + get(record, "categoryCode")));
            var uom =
                    unitsOfMeasure
                            .findByCode(
                                    require(record, "uomCode").toUpperCase(java.util.Locale.ROOT))
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown unit of measure: "
                                                            + get(record, "uomCode")));
            var taxClass =
                    taxClasses
                            .findWithRatesByCode(
                                    require(record, "taxClassCode")
                                            .toUpperCase(java.util.Locale.ROOT))
                            .orElseThrow(
                                    () ->
                                            new IllegalArgumentException(
                                                    "Unknown tax class: "
                                                            + get(record, "taxClassCode")));

            BigDecimal basePrice = parsePrice(require(record, "basePrice"));
            boolean sellByWeight = parseBoolean(get(record, "sellByWeight"), false);
            boolean inclusive = parseBoolean(get(record, "priceIncludesTax"), true);

            if (sellByWeight && !uom.isAllowsDecimal()) {
                throw new IllegalArgumentException(
                        "Sold by weight but unit %s does not allow decimals"
                                .formatted(uom.getCode()));
            }

            var existing = products.findBySku(sku);
            Product product = existing.orElseGet(Product::new);
            product.setSku(sku);
            product.setName(name);
            product.setCategory(category);
            product.setUnitOfMeasure(uom);
            product.setTaxClass(taxClass);
            product.setBasePrice(basePrice);
            product.setSellByWeight(sellByWeight);
            product.setPriceIncludesTax(inclusive);

            String brandCode = get(record, "brandCode");
            if (brandCode != null && !brandCode.isBlank()) {
                product.setBrand(
                        brands.findByCode(brandCode.toUpperCase(java.util.Locale.ROOT))
                                .orElseThrow(
                                        () ->
                                                new IllegalArgumentException(
                                                        "Unknown brand: " + brandCode)));
            }

            String barcodes = get(record, "barcodes");
            if (barcodes != null && !barcodes.isBlank()) {
                product.getBarcodes().clear();
                String[] codes = barcodes.split("\\|");
                for (int i = 0; i < codes.length; i++) {
                    String barcode = codes[i].trim();
                    if (!barcode.isEmpty()) {
                        product.addBarcode(barcode, i == 0);
                    }
                }
            }

            products.save(product);
            return existing.isPresent();
        }

        private static String require(CSVRecord record, String column) {
            String value = get(record, column);
            if (value == null || value.isBlank()) {
                throw new IllegalArgumentException("Missing required column: " + column);
            }
            return value.trim();
        }

        private static BigDecimal parsePrice(String value) {
            try {
                BigDecimal price = new BigDecimal(value.replace(",", ""));
                if (price.signum() < 0) {
                    throw new IllegalArgumentException("Price cannot be negative: " + value);
                }
                return price;
            } catch (NumberFormatException e) {
                throw new IllegalArgumentException("Not a valid price: " + value);
            }
        }

        private static boolean parseBoolean(String value, boolean fallback) {
            if (value == null || value.isBlank()) {
                return fallback;
            }
            return switch (value.trim().toLowerCase(java.util.Locale.ROOT)) {
                case "true", "yes", "y", "1" -> true;
                case "false", "no", "n", "0" -> false;
                default -> throw new IllegalArgumentException("Not a yes/no value: " + value);
            };
        }
    }
}
