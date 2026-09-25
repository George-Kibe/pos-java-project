package com.pos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pos.catalog.domain.Product;
import com.pos.events.EventJson;

import software.amazon.awssdk.services.s3.S3Client;

/**
 * The back office's catalog: categories, brands, units, tax classes and their rates, price lists,
 * promotions with a preview, and product pictures - each changed by the permission that owns it.
 */
@AutoConfigureMockMvc
@DisplayName("Managing the catalog")
class CatalogAdminIT extends CatalogTestBase {

    private static final UUID BRANCH = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private S3Client s3;

    private static RequestPostProcessor as(String... permissions) {
        UUID user = UUID.randomUUID();
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .build())
                .authorities(
                        java.util.Arrays.stream(permissions)
                                .map(SimpleGrantedAuthority::new)
                                .toArray(GrantedAuthority[]::new));
    }

    private static final RequestPostProcessor MANAGER =
            as("product:view", "product:manage", "price:manage", "promotion:manage", "tax:manage");

    private static String unique(String prefix) {
        return prefix + "_" + UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private ResultActions send(
            org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder request,
            RequestPostProcessor who,
            Object body)
            throws Exception {
        return mockMvc.perform(
                request.with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EventJson.write(body)));
    }

    private String id(ResultActions result) throws Exception {
        return EventJson.mapper()
                .readTree(result.andReturn().getResponse().getContentAsString())
                .get("id")
                .asString();
    }

    private Product juice(String price) {
        return newProductWithTaxClass(
                unique("JUICE"),
                "Mango juice",
                taxClasses.findAllByOrderByCodeAsc().stream()
                        .filter(taxClass -> taxClass.getCode().equals("STANDARD"))
                        .findFirst()
                        .orElseThrow(),
                price,
                true);
    }

    private ResultActions price(Product product, UUID branch, boolean member) throws Exception {
        return send(
                post("/api/v1/pricing/resolve"),
                as("product:view"),
                Map.of(
                        "lines",
                        List.of(Map.of("productId", product.getId(), "quantity", 1)),
                        "branchId",
                        branch,
                        "member",
                        member));
    }

    // --- reference data ---------------------------------------------------------------------

    @Test
    @DisplayName("categories nest and are renamed, but never under themselves; brands and units")
    void referenceData() throws Exception {
        String food =
                id(
                        send(
                                        post("/api/v1/categories"),
                                        MANAGER,
                                        Map.of("code", unique("food"), "name", "Food"))
                                .andExpect(status().isCreated()));
        String dairy =
                id(
                        send(
                                        post("/api/v1/categories"),
                                        MANAGER,
                                        Map.of(
                                                "code",
                                                unique("dairy"),
                                                "name",
                                                "Dairy",
                                                "parentId",
                                                food))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.parentId", is(food))));
        send(
                        put("/api/v1/categories/" + dairy),
                        MANAGER,
                        Map.of("name", "Milk and dairy", "parentId", food))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Milk and dairy")));
        send(put("/api/v1/categories/" + food), MANAGER, Map.of("name", "Food", "parentId", dairy))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.type", org.hamcrest.Matchers.endsWith("category.cycle")));

        String brandCode = unique("brand");
        String brand =
                id(
                        send(
                                        post("/api/v1/brands"),
                                        MANAGER,
                                        Map.of("code", brandCode, "name", "Brookside"))
                                .andExpect(status().isCreated()));
        send(post("/api/v1/brands"), MANAGER, Map.of("code", brandCode, "name", "Again"))
                .andExpect(status().isConflict());
        send(
                        put("/api/v1/brands/" + brand),
                        MANAGER,
                        Map.of("name", "Brookside Dairy", "active", false))
                .andExpect(jsonPath("$.active", is(false)));
        mockMvc.perform(get("/api/v1/brands").with(as("product:view")))
                .andExpect(jsonPath("$[*].name", hasItem("Brookside Dairy")));

        send(
                        post("/api/v1/units-of-measure"),
                        MANAGER,
                        Map.of(
                                "code",
                                unique("L"),
                                "name",
                                "Litre",
                                "allowsDecimal",
                                true,
                                "decimalPlaces",
                                3))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.decimalPlaces", is(3)));
        send(
                        post("/api/v1/units-of-measure"),
                        MANAGER,
                        Map.of(
                                "code",
                                unique("X"),
                                "name",
                                "Bad",
                                "allowsDecimal",
                                true,
                                "decimalPlaces",
                                7))
                .andExpect(status().isBadRequest());

        // Seeing the catalogue is not changing it.
        send(
                        post("/api/v1/brands"),
                        as("product:view"),
                        Map.of("code", unique("nope"), "name", "Nope"))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "a tax rate changes from a moment on, closing the one before; never backdated, never"
                    + " leaving a gap")
    void taxRatesAreVersioned() throws Exception {
        String code = unique("TEST_VAT");
        Instant start = Instant.now().plus(1, ChronoUnit.HOURS);
        String taxClass =
                id(
                        send(
                                        post("/api/v1/tax-classes"),
                                        MANAGER,
                                        Map.of("code", code, "name", "Test VAT", "rate", "0.16"))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.rates", hasSize(1))));

        send(
                        post("/api/v1/tax-classes/" + taxClass + "/rates"),
                        MANAGER,
                        Map.of("rate", "0.18", "validFrom", start.toString()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.rates", hasSize(2)))
                .andExpect(jsonPath("$.rates[0].rate", is(0.16)))
                .andExpect(jsonPath("$.rates[0].validTo").isNotEmpty())
                .andExpect(jsonPath("$.rates[1].rate", is(0.18)));

        send(
                        post("/api/v1/tax-classes/" + taxClass + "/rates"),
                        MANAGER,
                        Map.of(
                                "rate",
                                "0.2",
                                "validFrom",
                                Instant.now().minus(2, ChronoUnit.DAYS).toString()))
                .andExpect(status().isUnprocessableContent())
                .andExpect(
                        jsonPath("$.type", org.hamcrest.Matchers.endsWith("tax.rate_backdated")));
        send(
                        post("/api/v1/tax-classes/" + taxClass + "/rates"),
                        MANAGER,
                        Map.of("rate", "0.2", "validFrom", start.minusSeconds(60).toString()))
                .andExpect(
                        jsonPath(
                                "$.type",
                                org.hamcrest.Matchers.endsWith("tax.rate_not_after_latest")));
        send(post("/api/v1/tax-classes/" + taxClass + "/rates"), MANAGER, Map.of("rate", "1.5"))
                .andExpect(status().isBadRequest());

        // The default moves to the new class, and only one is ever the default.
        mockMvc.perform(put("/api/v1/tax-classes/" + taxClass + "/default").with(MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.isDefault", is(true)));
        mockMvc.perform(get("/api/v1/tax-classes").with(as("product:view")))
                .andExpect(jsonPath("$[?(@.isDefault == true)]", hasSize(1)))
                .andExpect(jsonPath("$[?(@.isDefault == true)].code", hasItem(code)));
        String standard =
                jdbc.sql("SELECT id FROM catalog.tax_classes WHERE code = 'STANDARD'")
                        .query(String.class)
                        .single();
        mockMvc.perform(put("/api/v1/tax-classes/" + standard + "/default").with(MANAGER))
                .andExpect(jsonPath("$.isDefault", is(true)));

        // Tax is its own permission.
        send(
                        post("/api/v1/tax-classes"),
                        as("product:manage"),
                        Map.of("code", unique("TEST_X"), "name", "X", "rate", "0.1"))
                .andExpect(status().isForbidden());
    }

    // --- price lists ------------------------------------------------------------------------

    @Test
    @DisplayName(
            "a branch price list prices its products at that branch only, and announces the change")
    void priceListsPriceAtTheirBranch() throws Exception {
        Product product = juice("100.00");
        String list =
                id(
                        send(
                                        post("/api/v1/price-lists"),
                                        MANAGER,
                                        Map.of(
                                                "code",
                                                unique("pl"),
                                                "name",
                                                "Town branch",
                                                "branchId",
                                                BRANCH,
                                                "priority",
                                                10))
                                .andExpect(status().isCreated()));

        send(
                        put("/api/v1/price-lists/" + list + "/items/" + product.getId()),
                        MANAGER,
                        Map.of("price", "90.00"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.price", is(90.0)))
                .andExpect(jsonPath("$.basePrice", is(100.0)));
        mockMvc.perform(get("/api/v1/price-lists/" + list + "/items").with(as("product:view")))
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].productName", is("Mango juice")));
        mockMvc.perform(get("/api/v1/price-lists").with(as("product:view")))
                .andExpect(jsonPath("$[?(@.id == '" + list + "')].items", hasItem(1)));

        price(product, BRANCH, false)
                .andExpect(jsonPath("$[0].unitPrice", is(90.0)))
                .andExpect(jsonPath("$[0].priceSource", is("PRICE_LIST")));
        price(product, UUID.randomUUID(), false).andExpect(jsonPath("$[0].unitPrice", is(100.0)));
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM catalog.outbox WHERE topic = 'pos.catalog.price-changed.v1'")
                                .query(Long.class)
                                .single())
                .isEqualTo(1);

        mockMvc.perform(
                        delete("/api/v1/price-lists/" + list + "/items/" + product.getId())
                                .with(MANAGER))
                .andExpect(status().isNoContent());
        price(product, BRANCH, false).andExpect(jsonPath("$[0].unitPrice", is(100.0)));

        // A list that ends before it starts is refused; product managers do not set prices.
        send(
                        post("/api/v1/price-lists"),
                        MANAGER,
                        Map.of(
                                "code",
                                unique("pl"),
                                "name",
                                "Bad",
                                "validFrom",
                                "2030-01-02T00:00:00Z",
                                "validTo",
                                "2030-01-01T00:00:00Z"))
                .andExpect(status().isBadRequest());
        send(
                        put("/api/v1/price-lists/" + list + "/items/" + product.getId()),
                        as("product:manage"),
                        Map.of("price", "1.00"))
                .andExpect(status().isForbidden());
    }

    // --- promotions -------------------------------------------------------------------------

    @Test
    @DisplayName(
            "a promotion is previewed before it is saved, then prices the till; stopped, it does not")
    void promotionsAreBuiltPreviewedAndStopped() throws Exception {
        Product product = juice("200.00");
        Map<String, Object> draft =
                new java.util.HashMap<>(
                        Map.of(
                                "code", unique("juice25"),
                                "name", "25% off juice",
                                "type", "PERCENTAGE_OFF",
                                "value", "0.25",
                                "rules",
                                        List.of(
                                                Map.of(
                                                        "scope",
                                                        "PRODUCT",
                                                        "scopeId",
                                                        product.getId()))));

        // The preview prices it as if it were live; nothing is saved.
        send(
                        post("/api/v1/promotions/preview"),
                        MANAGER,
                        Map.of("promotion", draft, "productId", product.getId(), "quantity", 2))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.subtotal", is(400.0)))
                .andExpect(jsonPath("$.discountTotal", is(100.0)))
                .andExpect(jsonPath("$.lineTotal", is(300.0)));
        price(product, BRANCH, false).andExpect(jsonPath("$[0].discountTotal", is(0.0)));

        String promotion =
                id(
                        send(post("/api/v1/promotions"), MANAGER, draft)
                                .andExpect(status().isCreated()));
        price(product, BRANCH, false)
                .andExpect(jsonPath("$[0].discountTotal", is(50.0)))
                .andExpect(jsonPath("$[0].discounts[0].name", is("25% off juice")));

        // An edit previews against the saved version's place, not beside it.
        draft.put("value", "0.10");
        send(
                        post("/api/v1/promotions/preview"),
                        MANAGER,
                        Map.of(
                                "promotion",
                                draft,
                                "promotionId",
                                promotion,
                                "productId",
                                product.getId(),
                                "quantity",
                                1))
                .andExpect(jsonPath("$.discountTotal", is(20.0)));
        send(put("/api/v1/promotions/" + promotion), MANAGER, draft)
                .andExpect(jsonPath("$.value", is(0.1)));

        mockMvc.perform(
                        put("/api/v1/promotions/" + promotion + "/active")
                                .param("active", "false")
                                .with(MANAGER))
                .andExpect(jsonPath("$.active", is(false)));
        price(product, BRANCH, false).andExpect(jsonPath("$[0].discountTotal", is(0.0)));

        // Promotions that could never apply are refused when they are made.
        draft.put("value", "25");
        draft.put("code", unique("bad"));
        send(post("/api/v1/promotions"), MANAGER, draft)
                .andExpect(status().isBadRequest())
                .andExpect(
                        jsonPath(
                                "$.type",
                                org.hamcrest.Matchers.endsWith("promotion.invalid_percentage")));
        draft.put("type", "BUY_X_GET_Y");
        send(post("/api/v1/promotions"), MANAGER, draft)
                .andExpect(
                        jsonPath(
                                "$.type",
                                org.hamcrest.Matchers.endsWith("promotion.invalid_quantities")));
        send(post("/api/v1/promotions"), as("product:manage"), draft)
                .andExpect(status().isForbidden());
    }

    // --- pictures ---------------------------------------------------------------------------

    private static final byte[] PNG = {
        (byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A, 0, 0, 0, 0x0D, 'I', 'H', 'D', 'R'
    };

    private long objectsFor(Product product) {
        return s3.listObjectsV2(
                        request ->
                                request.bucket("catalog-test-images")
                                        .prefix("products/" + product.getId() + "/"))
                .keyCount();
    }

    @Test
    @DisplayName(
            "a picture is kept in object storage and served back; a replaced one is deleted; a"
                    + " non-image is refused")
    void productPictures() throws Exception {
        Product product = juice("50.00");
        String url =
                EventJson.mapper()
                        .readTree(
                                mockMvc.perform(
                                                multipart(
                                                                "/api/v1/products/"
                                                                        + product.getId()
                                                                        + "/image")
                                                        .file(
                                                                new MockMultipartFile(
                                                                        "file",
                                                                        "juice.png",
                                                                        "image/png",
                                                                        PNG))
                                                        .with(MANAGER))
                                        .andExpect(status().isOk())
                                        .andExpect(
                                                jsonPath(
                                                        "$.imageUrl",
                                                        org.hamcrest.Matchers.startsWith(
                                                                "/api/v1/products/"
                                                                        + product.getId()
                                                                        + "/image?v=")))
                                        .andReturn()
                                        .getResponse()
                                        .getContentAsString())
                        .get("imageUrl")
                        .asString();

        mockMvc.perform(get(url).with(as("product:view")))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "image/png"))
                .andExpect(header().string("X-Content-Type-Options", "nosniff"))
                .andExpect(content().bytes(PNG));
        assertThat(objectsFor(product)).isEqualTo(1);

        // A replacement: a new URL, and the old object gone.
        byte[] jpeg = {
            (byte) 0xFF, (byte) 0xD8, (byte) 0xFF, (byte) 0xE0, 0, 0x10, 'J', 'F', 'I', 'F'
        };
        mockMvc.perform(
                        multipart("/api/v1/products/" + product.getId() + "/image")
                                .file(
                                        new MockMultipartFile(
                                                "file", "juice.jpg", "image/jpeg", jpeg))
                                .with(MANAGER))
                .andExpect(jsonPath("$.imageUrl", org.hamcrest.Matchers.not(url)));
        assertThat(objectsFor(product)).isEqualTo(1);

        // Declared a PNG, but an HTML page: refused by what it is, not what it says.
        mockMvc.perform(
                        multipart("/api/v1/products/" + product.getId() + "/image")
                                .file(
                                        new MockMultipartFile(
                                                "file",
                                                "x.png",
                                                "image/png",
                                                "<html><script>alert(1)</script>".getBytes()))
                                .with(MANAGER))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.type", org.hamcrest.Matchers.endsWith("image.unsupported")));
        mockMvc.perform(
                        multipart("/api/v1/products/" + product.getId() + "/image")
                                .file(new MockMultipartFile("file", "juice.png", "image/png", PNG))
                                .with(as("product:view")))
                .andExpect(status().isForbidden());

        mockMvc.perform(delete("/api/v1/products/" + product.getId() + "/image").with(MANAGER))
                .andExpect(jsonPath("$.imageUrl").doesNotExist());
        assertThat(objectsFor(product)).isZero();
        mockMvc.perform(
                        get("/api/v1/products/" + product.getId() + "/image")
                                .with(as("product:view")))
                .andExpect(status().isNotFound());
    }

    @Test
    @DisplayName(
            "the catalogue exports in the import's columns, and the file loads back as it left")
    void anExportedCatalogueLoadsBack() throws Exception {
        String sku = unique("CSV");
        String upload =
                "sku,name,categoryCode,uomCode,taxClassCode,basePrice,sellByWeight,priceIncludesTax,barcodes\n"
                        + sku
                        + ",\"=HYPERLINK(\"\"x\"\")\",GROCERY,EA,STANDARD,99.5000,no,yes,"
                        + "5060009990011|5060009990028\n";
        mockMvc.perform(
                        multipart("/api/v1/products/import")
                                .file(
                                        new MockMultipartFile(
                                                "file", "p.csv", "text/csv", upload.getBytes()))
                                .with(MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(1)));

        mockMvc.perform(get("/api/v1/products/export").with(as("product:view")))
                .andExpect(status().isForbidden());
        byte[] exported =
                mockMvc.perform(get("/api/v1/products/export").with(MANAGER))
                        .andExpect(status().isOk())
                        .andExpect(
                                header().string(
                                                "Content-Disposition",
                                                org.hamcrest.Matchers.containsString(
                                                        "products.csv")))
                        .andReturn()
                        .getResponse()
                        .getContentAsByteArray();
        String csv = new String(exported, java.nio.charset.StandardCharsets.UTF_8);

        assertThat(csv)
                .startsWith(
                        "\uFEFFsku,name,categoryCode,uomCode,taxClassCode,basePrice,brandCode,"
                                + "sellByWeight,priceIncludesTax,barcodes");
        String row = csv.lines().filter(line -> line.startsWith(sku)).findFirst().orElseThrow();
        // Opened in a spreadsheet, the name is text rather than a formula to run.
        assertThat(row)
                .contains("'=HYPERLINK")
                .contains("99.5000")
                .contains("5060009990011|5060009990028");

        // Loaded back with one price changed: every row lands, nothing is created, and the
        // guard does not end up in the name.
        byte[] edited =
                csv.replace(row, row.replace("99.5000", "101.0000"))
                        .getBytes(java.nio.charset.StandardCharsets.UTF_8);
        mockMvc.perform(
                        multipart("/api/v1/products/import")
                                .file(new MockMultipartFile("file", "p.csv", "text/csv", edited))
                                .with(MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.created", is(0)))
                .andExpect(jsonPath("$.failed", is(0)));
        Product reloaded = products.findBySku(sku).orElseThrow();
        assertThat(reloaded.getName()).isEqualTo("=HYPERLINK(\"x\")");
        assertThat(reloaded.getBasePrice()).isEqualByComparingTo("101");
        assertThat(products.findByBarcode("5060009990028")).isPresent();
    }

    @Test
    @DisplayName(
            "a cost with VAT is judged without it, against the category's target or the"
                    + " product's own")
    void costsAreCheckedWithoutVat() throws Exception {
        Product oil = newProduct(unique("OIL"), "Cooking oil 1L", "STANDARD", "232", true);
        UUID grocery = categories.findByCode("GROCERY").orElseThrow().getId();
        send(
                        put("/api/v1/categories/" + grocery + "/target-margin"),
                        as("product:manage"),
                        Map.of("targetMargin", 0.15))
                .andExpect(status().isForbidden());
        send(
                        put("/api/v1/categories/" + grocery + "/target-margin"),
                        MANAGER,
                        Map.of("targetMargin", 0.15))
                .andExpect(status().isOk());

        Map<String, Object> check =
                Map.of(
                        "costIncludesTax",
                        true,
                        "lines",
                        List.of(Map.of("productId", oil.getId(), "unitCost", 220.40)));
        // 220.40 on the invoice is 190 without VAT: 5% on a 200 net price, below 15%.
        send(post("/api/v1/pricing/cost-check"), as("product:view"), check)
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].taxRate", is(0.16)))
                .andExpect(jsonPath("$[0].netUnitCost", is(190.0)))
                .andExpect(jsonPath("$[0].netPrice", is(200.0)))
                .andExpect(jsonPath("$[0].margin", is(0.05)))
                .andExpect(jsonPath("$[0].status", is("BELOW_TARGET")))
                .andExpect(jsonPath("$[0].suggestedPrice", is(260.0)));

        // A product's own target overrides the category's; null follows it again.
        send(
                        put("/api/v1/products/" + oil.getId() + "/target-margin"),
                        MANAGER,
                        Map.of("targetMargin", 0.04))
                .andExpect(jsonPath("$.targetMargin", is(0.04)));
        send(post("/api/v1/pricing/cost-check"), as("product:view"), check)
                .andExpect(jsonPath("$[0].status", is("OK")));
        send(
                        put("/api/v1/products/" + oil.getId() + "/target-margin"),
                        MANAGER,
                        java.util.Collections.singletonMap("targetMargin", null))
                .andExpect(jsonPath("$.targetMargin").doesNotExist());
        send(
                        put("/api/v1/categories/" + grocery + "/target-margin"),
                        MANAGER,
                        Map.of("targetMargin", 1))
                .andExpect(status().isBadRequest());
    }
}
