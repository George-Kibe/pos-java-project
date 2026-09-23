package com.pos.catalog;

import java.math.BigDecimal;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.pos.catalog.domain.Category;
import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.TaxClass;
import com.pos.catalog.domain.TaxRate;
import com.pos.catalog.domain.UnitOfMeasure;
import com.pos.catalog.repository.CategoryRepository;
import com.pos.catalog.repository.ProductRepository;
import com.pos.catalog.repository.TaxClassRepository;
import com.pos.catalog.repository.UnitOfMeasureRepository;

/**
 * Shared setup against a real PostgreSQL, with the migrations and their seed data applied.
 *
 * <p>Using the real seed matters here: the tax classes and rates under test are the ones a
 * deployment actually gets, so a mistake in the migration shows up as a failing price rather than
 * being papered over by a fixture.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class CatalogTestBase {

    /** Started once for the JVM; see the note in the auth test base about @Container. */
    // Never closed on purpose: it lives for the whole JVM and Testcontainers' reaper removes it.
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    static {
        POSTGRES.start();
    }

    @DynamicPropertySource
    static void datasource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }

    @Autowired protected ProductRepository products;
    @Autowired protected CategoryRepository categories;
    @Autowired protected TaxClassRepository taxClasses;
    @Autowired protected UnitOfMeasureRepository unitsOfMeasure;
    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void clearProducts() {
        // Reference data comes from the migration and stays; products are per test. Tax classes a
        // test created for itself go too - a test must never mutate the seeded ones, because the
        // rates they carry are shared by every other test in the class.
        jdbc.sql("DELETE FROM catalog.price_list_items").update();
        jdbc.sql("DELETE FROM catalog.price_lists").update();
        jdbc.sql("DELETE FROM catalog.promotion_rules").update();
        jdbc.sql("DELETE FROM catalog.promotions").update();
        jdbc.sql("DELETE FROM catalog.product_barcodes").update();
        jdbc.sql("DELETE FROM catalog.products").update();
        jdbc.sql("DELETE FROM catalog.outbox").update();
        jdbc.sql(
                        "DELETE FROM catalog.tax_rates WHERE tax_class_id IN"
                                + " (SELECT id FROM catalog.tax_classes WHERE code LIKE 'TEST\\_%')")
                .update();
        jdbc.sql("DELETE FROM catalog.tax_classes WHERE code LIKE 'TEST\\_%'").update();
    }

    /**
     * A tax class of this test's own, with one rate from the beginning of time.
     *
     * <p>Tests that need to change rates create their own rather than editing the seeded classes,
     * which every other test depends on.
     */
    protected TaxClass newTaxClass(String code, String rate) {
        TaxClass taxClass = new TaxClass("TEST_" + code, "Test " + code);
        taxClass.getRates()
                .add(
                        new TaxRate(
                                taxClass,
                                new BigDecimal(rate),
                                java.time.Instant.parse("2000-01-01T00:00:00Z")));
        return taxClasses.save(taxClass);
    }

    protected Product newProductWithTaxClass(
            String sku, String name, TaxClass taxClass, String basePrice, boolean inclusive) {
        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setCategory(categories.findByCode("GROCERY").orElseThrow());
        product.setUnitOfMeasure(unitsOfMeasure.findByCode("EA").orElseThrow());
        product.setTaxClass(taxClass);
        product.setBasePrice(new BigDecimal(basePrice));
        product.setPriceIncludesTax(inclusive);
        return products.save(product);
    }

    protected Product newProduct(
            String sku, String name, String taxClassCode, String basePrice, boolean inclusive) {
        return newProduct(sku, name, taxClassCode, basePrice, inclusive, "EA", false);
    }

    protected Product newProduct(
            String sku,
            String name,
            String taxClassCode,
            String basePrice,
            boolean inclusive,
            String uomCode,
            boolean sellByWeight) {

        Category category = categories.findByCode("GROCERY").orElseThrow();
        UnitOfMeasure uom = unitsOfMeasure.findByCode(uomCode).orElseThrow();
        TaxClass taxClass = taxClasses.findWithRatesByCode(taxClassCode).orElseThrow();

        Product product = new Product();
        product.setSku(sku);
        product.setName(name);
        product.setCategory(category);
        product.setUnitOfMeasure(uom);
        product.setTaxClass(taxClass);
        product.setBasePrice(new BigDecimal(basePrice));
        product.setPriceIncludesTax(inclusive);
        product.setSellByWeight(sellByWeight);
        return products.save(product);
    }
}
