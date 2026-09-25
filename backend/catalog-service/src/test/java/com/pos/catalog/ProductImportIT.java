package com.pos.catalog;

import static org.assertj.core.api.Assertions.assertThat;

import java.io.ByteArrayInputStream;
import java.nio.charset.StandardCharsets;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.catalog.service.ProductImportService;

/** Bulk loading, including what happens when part of the file is wrong. */
class ProductImportIT extends CatalogTestBase {

    @Autowired private ProductImportService importer;

    private static final String HEADER =
            "sku,name,categoryCode,uomCode,taxClassCode,basePrice,sellByWeight,priceIncludesTax,barcodes\n";

    @Test
    void loadsAFileOfProducts() {
        String csv =
                HEADER
                        + "BULK-1,Cooking oil 1L,GROCERY,EA,STANDARD,320.00,no,yes,5060001000011\n"
                        + "BULK-2,Bread white,BAKERY,EA,ZERO_RATED,55.00,no,yes,5060001000028\n"
                        + "BULK-3,Tomatoes,FRESH,KG,ZERO_RATED,250.00,yes,yes,\n";

        ProductImportService.ImportReport report = importer.importFrom(stream(csv));

        assertThat(report.created()).isEqualTo(3);
        assertThat(report.failed()).isZero();
        assertThat(products.findBySku("BULK-3").orElseThrow().isSellByWeight()).isTrue();
        assertThat(products.findByBarcode("5060001000011")).isPresent();
    }

    @Test
    @DisplayName("a product name containing a comma survives, because the parser is a real one")
    void handlesQuotedFields() {
        String csv =
                HEADER + "\"BULK-4\",\"Rice, basmati 5kg\",GROCERY,EA,STANDARD,1250.00,no,yes,\n";

        assertThat(importer.importFrom(stream(csv)).created()).isEqualTo(1);
        assertThat(products.findBySku("BULK-4").orElseThrow().getName())
                .isEqualTo("Rice, basmati 5kg");
    }

    @Test
    @DisplayName("good rows land even when others fail, and each failure says why")
    void oneBadRowDoesNotRejectTheFile() {
        String csv =
                HEADER
                        + "GOOD-1,Valid product,GROCERY,EA,STANDARD,100.00,no,yes,\n"
                        + "BAD-1,Unknown tax class,GROCERY,EA,NOT_A_CLASS,100.00,no,yes,\n"
                        + "BAD-2,Bad price,GROCERY,EA,STANDARD,not-a-number,no,yes,\n"
                        + "BAD-3,Weighed in whole units,GROCERY,EA,STANDARD,100.00,yes,yes,\n"
                        + "GOOD-2,Another valid one,GROCERY,EA,STANDARD,200.00,no,yes,\n";

        ProductImportService.ImportReport report = importer.importFrom(stream(csv));

        // Rejecting the whole file for three bad rows would make a thousand-row load impossible
        // to get right, and leave whoever prepared it guessing.
        assertThat(report.created()).isEqualTo(2);
        assertThat(report.failed()).isEqualTo(3);
        assertThat(products.findBySku("GOOD-1")).isPresent();
        assertThat(products.findBySku("GOOD-2")).isPresent();
        assertThat(products.findBySku("BAD-1")).isEmpty();

        assertThat(report.errors()).hasSize(3);
        assertThat(report.errors().get(0).sku()).isEqualTo("BAD-1");
        assertThat(report.errors().get(0).message()).contains("Unknown tax class");
        assertThat(report.errors().get(1).message()).contains("Not a valid price");
        assertThat(report.errors().get(2).message()).contains("does not allow decimals");
        // The line number is what makes the report usable against the original file.
        assertThat(report.errors().get(0).line()).isEqualTo(2);
    }

    @Test
    @DisplayName("re-importing a corrected file updates rather than failing on duplicates")
    void reimportUpdatesExistingProducts() {
        importer.importFrom(
                stream(HEADER + "RERUN-1,First name,GROCERY,EA,STANDARD,100.00,no,yes,\n"));

        ProductImportService.ImportReport second =
                importer.importFrom(
                        stream(
                                HEADER
                                        + "RERUN-1,Corrected name,GROCERY,EA,STANDARD,150.00,no,yes,\n"));

        assertThat(second.created()).isZero();
        assertThat(second.updated()).isEqualTo(1);
        assertThat(products.findBySku("RERUN-1").orElseThrow().getName())
                .isEqualTo("Corrected name");
        assertThat(products.findBySku("RERUN-1").orElseThrow().getBasePrice())
                .isEqualByComparingTo("150.00");
    }

    @Test
    @DisplayName("re-importing a product's own barcodes in another order makes the first primary")
    void reimportReordersBarcodes() {
        importer.importFrom(
                stream(
                        HEADER
                                + "REORDER-1,Soap,GROCERY,EA,STANDARD,80.00,no,yes,5060007770011|5060007770028\n"));

        ProductImportService.ImportReport second =
                importer.importFrom(
                        stream(
                                HEADER
                                        + "REORDER-1,Soap,GROCERY,EA,STANDARD,80.00,no,yes,5060007770028|5060007770011\n"));

        assertThat(second.failed()).isZero();
        List<String> held =
                jdbc.sql(
                                "SELECT b.barcode FROM product_barcodes b JOIN products p"
                                        + " ON p.id = b.product_id WHERE p.sku = 'REORDER-1'"
                                        + " ORDER BY b.is_primary DESC, b.barcode")
                        .query(String.class)
                        .list();
        assertThat(held).containsExactly("5060007770028", "5060007770011");
    }

    @Test
    @DisplayName("a barcode another product holds is refused in words, not in SQL")
    void aBarcodeHeldElsewhereIsExplained() {
        importer.importFrom(
                stream(HEADER + "OWNER-1,Milk,GROCERY,EA,STANDARD,60.00,no,yes,5060006660011\n"));

        ProductImportService.ImportReport report =
                importer.importFrom(
                        stream(
                                HEADER
                                        + "TAKER-1,Juice,GROCERY,EA,STANDARD,90.00,no,yes,5060006660011\n"));

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.errors().get(0).message())
                .isEqualTo("A barcode on this row already belongs to another product");
        assertThat(products.findBySku("TAKER-1")).isEmpty();
    }

    @Test
    void missingRequiredColumnsAreReportedPerRow() {
        String csv = HEADER + ",No SKU at all,GROCERY,EA,STANDARD,100.00,no,yes,\n";

        ProductImportService.ImportReport report = importer.importFrom(stream(csv));

        assertThat(report.failed()).isEqualTo(1);
        assertThat(report.errors().get(0).message()).contains("Missing required column: sku");
    }

    private static ByteArrayInputStream stream(String csv) {
        return new ByteArrayInputStream(csv.getBytes(StandardCharsets.UTF_8));
    }
}
