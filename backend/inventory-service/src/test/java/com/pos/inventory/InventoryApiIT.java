package com.pos.inventory;

import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.delete;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pos.events.EventJson;
import com.pos.inventory.service.StockService;

/** The inventory HTTP surface. */
@AutoConfigureMockMvc
class InventoryApiIT extends InventoryTestBase {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private StockService stock;

    /** A token for someone assigned to BRANCH only. */
    private static RequestPostProcessor at(UUID branch, String... permissions) {
        UUID userId = UUID.randomUUID();
        GrantedAuthority[] authorities =
                Arrays.stream(permissions)
                        .map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new);

        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(userId.toString())
                                .claim("uid", userId.toString())
                                .claim("perms", List.of(permissions))
                                .claim("branches", List.of(branch.toString()))
                                .build())
                .authorities(authorities);
    }

    // --- authorization ----------------------------------------------------------

    @Test
    void stockIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/stock").param("branchId", BRANCH.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a manager at one branch cannot read another branch's stock")
    void stockIsBranchScoped() throws Exception {
        mockMvc.perform(
                        get("/api/v1/stock")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isOk());

        // Assigned to BRANCH, asking about OTHER_BRANCH.
        mockMvc.perform(
                        get("/api/v1/stock")
                                .param("branchId", OTHER_BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("branch.access_denied")));
    }

    @Test
    void viewingAndAdjustingAreSeparatePermissions() throws Exception {
        UUID product = stockOf("PERM", "10");

        mockMvc.perform(
                        get("/api/v1/stock/" + product)
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isOk());

        mockMvc.perform(
                        put("/api/v1/stock/" + product + "/reorder-point")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reorderPoint\":5,\"reorderQuantity\":24}"))
                .andExpect(status().isForbidden());
    }

    // --- stock ------------------------------------------------------------------

    @Test
    void listsStockWithAvailability() throws Exception {
        UUID product = stockOf("LIST-1", "12");

        mockMvc.perform(
                        get("/api/v1/stock")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))))
                .andExpect(jsonPath("$.content[0].quantityOnHand", is(12.000)))
                .andExpect(jsonPath("$.content[0].quantityAvailable", is(12.000)));

        mockMvc.perform(
                        get("/api/v1/stock/" + product + "/batches")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].batchNumber", is("LIST-1-B1")));
    }

    @Test
    @DisplayName("the ledger is readable, so a quantity can always be accounted for")
    void movementsAreReadable() throws Exception {
        UUID product = stockOf("LEDGER-1", "9");

        mockMvc.perform(
                        get("/api/v1/stock/" + product + "/movements")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)))
                .andExpect(jsonPath("$.content[0].type", is("RECEIPT")))
                .andExpect(jsonPath("$.content[0].quantity", is(9.000)));
    }

    @Test
    void reportsLowStockAndAcceptsAReorderPoint() throws Exception {
        UUID product = stockOf("LOW-1", "3");

        mockMvc.perform(
                        put("/api/v1/stock/" + product + "/reorder-point")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:adjust"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reorderPoint\":5,\"reorderQuantity\":24}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.belowReorderPoint", is(true)));

        mockMvc.perform(
                        get("/api/v1/stock/low")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku", is("LOW-1")));
    }

    @Test
    @DisplayName("the reconciliation report is empty, and answerable on demand")
    void reconciliationIsExposed() throws Exception {
        stockOf("RECON-1", "20");

        mockMvc.perform(get("/api/v1/stock/reconciliation").with(at(BRANCH, "inventory:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    @Test
    void anUnknownProductIsNotFound() throws Exception {
        mockMvc.perform(
                        get("/api/v1/stock/" + UUID.randomUUID())
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(status().isNotFound());
    }

    // --- adjustments ------------------------------------------------------------

    @Test
    void draftsAndPostsAnAdjustment() throws Exception {
        UUID product = stockOf("ADJ-1", "20");

        String body =
                EventJson.write(
                        Map.of(
                                "branchId",
                                BRANCH.toString(),
                                "reasonCode",
                                "DAMAGE",
                                "notes",
                                "Dropped a crate",
                                "lines",
                                List.of(
                                        Map.of(
                                                "productId", product.toString(),
                                                "sku", "ADJ-1",
                                                "quantityDelta", "-4"))));

        String created =
                mockMvc.perform(
                                post("/api/v1/adjustments")
                                        .with(at(BRANCH, "inventory:adjust"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status", is("DRAFT")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String adjustmentId = EventJson.mapper().readTree(created).get("id").asString();

        mockMvc.perform(
                        post("/api/v1/adjustments/" + adjustmentId + "/post")
                                .with(at(BRANCH, "inventory:adjust")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("POSTED")))
                .andExpect(jsonPath("$.postedAt").exists());

        mockMvc.perform(
                        get("/api/v1/stock/" + product)
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(jsonPath("$.quantityOnHand", is(16.000)));
    }

    @Test
    void rejectsAnAdjustmentWithNoLines() throws Exception {
        mockMvc.perform(
                        post("/api/v1/adjustments")
                                .with(at(BRANCH, "inventory:adjust"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "branchId", BRANCH.toString(),
                                                        "reasonCode", "OTHER",
                                                        "lines", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("request.validation_failed")));
    }

    // --- stock takes ------------------------------------------------------------

    @Test
    @DisplayName("a count sheet is opened, counted and posted over HTTP")
    void stockTakeLifecycle() throws Exception {
        UUID product = stockOf("COUNT-1", "20");

        String opened =
                mockMvc.perform(
                                post("/api/v1/stock-takes")
                                        .with(at(BRANCH, "stocktake:manage"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                EventJson.write(
                                                        Map.of(
                                                                "reference", "ST-API-1",
                                                                "branchId", BRANCH.toString(),
                                                                "notes", "API count"))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status", is("COUNTING")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String stockTakeId = EventJson.mapper().readTree(opened).get("id").asString();
        UUID stockItemId = items.findByProductIdAndBranchId(product, BRANCH).orElseThrow().getId();

        mockMvc.perform(
                        post("/api/v1/stock-takes/" + stockTakeId + "/counts")
                                .with(at(BRANCH, "stocktake:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "counts",
                                                        List.of(
                                                                Map.of(
                                                                        "stockItemId",
                                                                                stockItemId
                                                                                        .toString(),
                                                                        "countedQuantity", "18",
                                                                        "notes", "Two missing"))))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.varianceCount", is(1)))
                .andExpect(jsonPath("$.lines[0].variance", is(-2.000)));

        mockMvc.perform(
                        post("/api/v1/stock-takes/" + stockTakeId + "/post")
                                .with(at(BRANCH, "stocktake:manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("POSTED")));

        mockMvc.perform(
                        get("/api/v1/stock/" + product)
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(jsonPath("$.quantityOnHand", is(18.000)));
    }

    // --- transfers and reservations ---------------------------------------------

    @Test
    void transferLifecycle() throws Exception {
        UUID product = stockOf("TRF-API", "30");

        String drafted =
                mockMvc.perform(
                                post("/api/v1/transfers")
                                        .with(at(BRANCH, "transfer:manage"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                EventJson.write(
                                                        Map.of(
                                                                "reference", "TRF-API-1",
                                                                "fromBranchId", BRANCH.toString(),
                                                                "toBranchId",
                                                                        OTHER_BRANCH.toString(),
                                                                "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "productId",
                                                                                                product
                                                                                                        .toString(),
                                                                                        "sku",
                                                                                                "TRF-API",
                                                                                        "quantity",
                                                                                                "10"))))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status", is("DRAFT")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String transferId = EventJson.mapper().readTree(drafted).get("id").asString();

        mockMvc.perform(
                        post("/api/v1/transfers/" + transferId + "/dispatch")
                                .with(at(BRANCH, "transfer:manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("IN_TRANSIT")));

        mockMvc.perform(
                        post("/api/v1/transfers/" + transferId + "/receive")
                                .with(at(BRANCH, "transfer:manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("RECEIVED")));
    }

    @Test
    void reservationLifecycle() throws Exception {
        UUID product = stockOf("RES-API", "10");
        UUID cartId = UUID.randomUUID();

        mockMvc.perform(
                        post("/api/v1/reservations")
                                .with(at(BRANCH, "cart:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "productId", product.toString(),
                                                        "branchId", BRANCH.toString(),
                                                        "quantity", "3",
                                                        "referenceType", "Cart",
                                                        "referenceId", cartId.toString()))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("HELD")));

        mockMvc.perform(
                        get("/api/v1/stock/" + product)
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "inventory:view")))
                .andExpect(jsonPath("$.quantityOnHand", is(10.000)))
                .andExpect(jsonPath("$.quantityAvailable", is(7.000)));

        mockMvc.perform(
                        delete("/api/v1/reservations")
                                .param("referenceType", "Cart")
                                .param("referenceId", cartId.toString())
                                .with(at(BRANCH, "cart:manage")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.released", is(1)));
    }

    // --- helpers ----------------------------------------------------------------

    private UUID stockOf(String sku, String quantity) {
        UUID product = UUID.randomUUID();
        stock.receive(
                BRANCH,
                UUID.randomUUID(),
                "TestSetup",
                List.of(
                        new StockService.ReceiptLine(
                                product,
                                sku,
                                new BigDecimal(quantity),
                                sku + "-B1",
                                LocalDate.now().plusMonths(6),
                                new BigDecimal("100.00"),
                                "KES")));
        return product;
    }
}
