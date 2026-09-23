package com.pos.catalog;

import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.barcode.Ean13;
import com.pos.catalog.service.ProductService;
import com.pos.events.EventJson;

/** The catalog HTTP surface: pricing, scanning, products and reference data. */
@AutoConfigureMockMvc
class CatalogApiIT extends CatalogTestBase {

    @Autowired private MockMvc mockMvc;
    @Autowired private ProductService productService;

    private static RequestPostProcessor withPermissions(String... permissions) {
        UUID userId = UUID.randomUUID();
        org.springframework.security.core.GrantedAuthority[] authorities =
                java.util.Arrays.stream(permissions)
                        .map(SimpleGrantedAuthority::new)
                        .toArray(org.springframework.security.core.GrantedAuthority[]::new);

        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(userId.toString())
                                .claim("uid", userId.toString())
                                .claim("perms", List.of(permissions))
                                .build())
                .authorities(authorities);
    }

    // --- authorization ----------------------------------------------------------

    @Test
    void theCatalogIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/products")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/categories")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("viewing and managing are separate permissions")
    void viewingAndManagingAreSeparate() throws Exception {
        mockMvc.perform(get("/api/v1/products").with(withPermissions("product:view")))
                .andExpect(status().isOk());

        // A cashier can price a basket; they cannot edit the catalog to do it.
        // The body is valid on purpose: request binding happens before method security, so an
        // invalid body would be answered 400 and never reach the authorization check.
        String validBody =
                EventJson.write(
                        Map.of(
                                "sku",
                                "DENIED-1",
                                "name",
                                "Should not be created",
                                "categoryId",
                                categories.findByCode("GROCERY").orElseThrow().getId(),
                                "unitOfMeasureId",
                                unitsOfMeasure.findByCode("EA").orElseThrow().getId(),
                                "taxClassId",
                                taxClasses.findWithRatesByCode("STANDARD").orElseThrow().getId(),
                                "basePrice",
                                "10.00"));

        mockMvc.perform(
                        post("/api/v1/products")
                                .with(withPermissions("product:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(validBody))
                .andExpect(status().isForbidden());
    }

    // --- products ---------------------------------------------------------------

    @Test
    void createsAndReadsBackAProduct() throws Exception {
        String body =
                EventJson.write(
                        Map.of(
                                "sku",
                                "api-1",
                                "name",
                                "API product",
                                "categoryId",
                                categories.findByCode("GROCERY").orElseThrow().getId(),
                                "unitOfMeasureId",
                                unitsOfMeasure.findByCode("EA").orElseThrow().getId(),
                                "taxClassId",
                                taxClasses.findWithRatesByCode("STANDARD").orElseThrow().getId(),
                                "basePrice",
                                "116.00",
                                "priceIncludesTax",
                                true,
                                "sellByWeight",
                                false,
                                "barcodes",
                                List.of("5060001111111")));

        mockMvc.perform(
                        post("/api/v1/products")
                                .with(withPermissions("product:manage", "product:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isCreated())
                .andExpect(header().exists("Location"))
                // SKUs are normalised, so a lookup by SKU is unambiguous.
                .andExpect(jsonPath("$.sku", is("API-1")))
                .andExpect(jsonPath("$.barcodes", hasSize(1)));
    }

    @Test
    void rejectsAProductWithoutTheFieldsItNeeds() throws Exception {
        mockMvc.perform(
                        post("/api/v1/products")
                                .with(withPermissions("product:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"sku\":\"\",\"name\":\"\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("request.validation_failed")))
                .andExpect(jsonPath("$.errors", hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    // --- pricing ----------------------------------------------------------------

    @Test
    @DisplayName("the pricing endpoint returns the whole working, not just a total")
    void pricingReturnsTheFullBreakdown() throws Exception {
        Product soap = newProduct("API-SOAP", "Bar soap", "STANDARD", "116.00", true);

        String body =
                EventJson.write(
                        Map.of(
                                "lines",
                                List.of(
                                        Map.of(
                                                "productId",
                                                soap.getId().toString(),
                                                "quantity",
                                                "2")),
                                "member",
                                false));

        mockMvc.perform(
                        post("/api/v1/pricing/resolve")
                                .with(withPermissions("product:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku", is("API-SOAP")))
                .andExpect(jsonPath("$[0].subtotal", is(232.0000)))
                .andExpect(jsonPath("$[0].net", is(200.0000)))
                .andExpect(jsonPath("$[0].tax", is(32.0000)))
                .andExpect(jsonPath("$[0].lineTotal", is(232.0000)))
                .andExpect(jsonPath("$[0].taxClassCode", is("STANDARD")))
                .andExpect(jsonPath("$[0].priceSource", is("BASE_PRICE")))
                .andExpect(jsonPath("$[0].currency", is("KES")));
    }

    @Test
    @DisplayName("a basket is priced in one call so every line shares an instant")
    void pricesSeveralLinesAtOnce() throws Exception {
        Product soap = newProduct("API-SOAP-2", "Bar soap", "STANDARD", "116.00", true);
        Product bread = newProduct("API-BREAD", "Bread", "ZERO_RATED", "55.00", true);

        String body =
                EventJson.write(
                        Map.of(
                                "lines",
                                List.of(
                                        Map.of(
                                                "productId",
                                                soap.getId().toString(),
                                                "quantity",
                                                "1"),
                                        Map.of("sku", bread.getSku(), "quantity", "3"))));

        mockMvc.perform(
                        post("/api/v1/pricing/resolve")
                                .with(withPermissions("product:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].tax", is(16.0000)))
                // Bread is zero rated: three loaves, no tax.
                .andExpect(jsonPath("$[1].tax", is(0.0000)))
                .andExpect(jsonPath("$[1].lineTotal", is(165.0000)));
    }

    @Test
    void anUnknownProductIsNotFound() throws Exception {
        String body =
                EventJson.write(
                        Map.of(
                                "lines",
                                List.of(
                                        Map.of(
                                                "productId",
                                                UUID.randomUUID().toString(),
                                                "quantity",
                                                "1"))));

        mockMvc.perform(
                        post("/api/v1/pricing/resolve")
                                .with(withPermissions("product:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("product.not_found")));
    }

    // --- scanning ---------------------------------------------------------------

    @Test
    @DisplayName("scanning a scale barcode returns the weight the label carries")
    void scanEndpointDecodesAScaleBarcode() throws Exception {
        newProduct("12345", "Tomatoes", "ZERO_RATED", "250.00", true, "KG", true);
        String barcode = Ean13.withCheckDigit("201234501235");

        mockMvc.perform(
                        get("/api/v1/products/scan")
                                .param("barcode", barcode)
                                .with(withPermissions("product:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scaleBarcode", is(true)))
                .andExpect(jsonPath("$.quantityFromBarcode", is(1.235)))
                .andExpect(jsonPath("$.price.lineTotal", is(308.7500)))
                .andExpect(jsonPath("$.scaleRuleName", notNullValue()));
    }

    @Test
    void scanEndpointHandlesAnOrdinaryBarcode() throws Exception {
        Product soap = newProduct("API-SOAP-3", "Bar soap", "STANDARD", "116.00", true);
        productService.replaceBarcodes(soap.getId(), List.of("5060002222222"));

        mockMvc.perform(
                        get("/api/v1/products/scan")
                                .param("barcode", "5060002222222")
                                .with(withPermissions("product:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.scaleBarcode", is(false)))
                .andExpect(jsonPath("$.quantityFromBarcode", is(1)))
                .andExpect(jsonPath("$.price.sku", is("API-SOAP-3")));
    }

    // --- reference data ---------------------------------------------------------

    @Test
    @DisplayName("tax classes are served with their effective-dated rates")
    void referenceDataIsAvailable() throws Exception {
        mockMvc.perform(get("/api/v1/tax-classes").with(withPermissions("product:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='STANDARD')].rates[0].rate", hasSize(1)));

        mockMvc.perform(get("/api/v1/units-of-measure").with(withPermissions("product:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[?(@.code=='KG')].allowsDecimal", is(List.of(true))));

        mockMvc.perform(get("/api/v1/categories").with(withPermissions("product:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(org.hamcrest.Matchers.greaterThan(0))));
    }

    @Test
    void createsACategory() throws Exception {
        String code = "CAT" + System.nanoTime() % 100000;
        mockMvc.perform(
                        post("/api/v1/categories")
                                .with(withPermissions("product:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of("code", code, "name", "New category"))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.code", is(code.toUpperCase(java.util.Locale.ROOT))));
    }

    @Test
    @DisplayName("a product sold by weight needs a unit that allows decimals")
    void weightSoldProductsNeedADecimalUnit() throws Exception {
        String body =
                EventJson.write(
                        Map.of(
                                "sku",
                                "BAD-WEIGH",
                                "name",
                                "Sold by weight in whole units",
                                "categoryId",
                                categories.findByCode("GROCERY").orElseThrow().getId(),
                                // EA does not allow decimals, so the till could only ever ring up
                                // 1.
                                "unitOfMeasureId",
                                unitsOfMeasure.findByCode("EA").orElseThrow().getId(),
                                "taxClassId",
                                taxClasses.findWithRatesByCode("STANDARD").orElseThrow().getId(),
                                "basePrice",
                                new BigDecimal("250.00"),
                                "priceIncludesTax",
                                true,
                                "sellByWeight",
                                true));

        mockMvc.perform(
                        post("/api/v1/products")
                                .with(withPermissions("product:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("product.weight_needs_decimal_uom")));
    }
}
