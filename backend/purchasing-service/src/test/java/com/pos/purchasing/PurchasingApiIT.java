package com.pos.purchasing;

import static org.hamcrest.Matchers.contains;
import static org.hamcrest.Matchers.containsInAnyOrder;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

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

/** The purchasing HTTP surface. */
@AutoConfigureMockMvc
class PurchasingApiIT extends PurchasingTestBase {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();
    private static final UUID FLOUR = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;

    /** A token for someone assigned to one branch, carrying exactly these permissions. */
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
    void purchasingIsNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/suppliers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/purchase-orders").param("branchId", BRANCH.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("raising an order and approving it are separate permissions")
    void approvalIsSeparatelyPermissioned() throws Exception {
        String supplierId = createSupplier("SUP-PERM");
        String orderId = createOrder(supplierId);

        // A buyer can submit but not approve their own order.
        mockMvc.perform(
                        post("/api/v1/purchase-orders/" + orderId + "/submit")
                                .with(at(BRANCH, "purchase:create")))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/purchase-orders/" + orderId + "/approve")
                                .with(at(BRANCH, "purchase:create")))
                .andExpect(status().isForbidden());

        mockMvc.perform(
                        post("/api/v1/purchase-orders/" + orderId + "/approve")
                                .with(at(BRANCH, "purchase:approve")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.approvedBy", notNullValue()));
    }

    @Test
    @DisplayName("a manager at one branch cannot see another branch's orders")
    void ordersAreBranchScoped() throws Exception {
        mockMvc.perform(
                        get("/api/v1/purchase-orders")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/purchase-orders")
                                .param("branchId", OTHER_BRANCH.toString())
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("branch.access_denied")));
    }

    @Test
    @DisplayName("orders list newest first, so the one just raised is on the first page")
    void ordersListNewestFirst() throws Exception {
        String supplierId = createSupplier("SUP-ORDER");
        String older = createOrder(supplierId);
        String newer = createOrder(supplierId);

        mockMvc.perform(
                        get("/api/v1/purchase-orders")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].id", is(newer)))
                .andExpect(jsonPath("$.content[1].id", is(older)));
    }

    @Test
    @DisplayName("a search among active suppliers leaves out one on hold")
    void searchingActiveSuppliersLeavesOutOnesOnHold() throws Exception {
        String active = createSupplier("SUP-FIND-A");
        String held = createSupplier("SUP-FIND-H");
        mockMvc.perform(
                        put("/api/v1/suppliers/" + held + "/status")
                                .with(at(BRANCH, "supplier:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("status", "ON_HOLD"))))
                .andExpect(status().isOk());

        mockMvc.perform(
                        get("/api/v1/suppliers")
                                .param("q", "SUP-FIND")
                                .param("status", "ACTIVE")
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[*].id", contains(active)));
        mockMvc.perform(
                        get("/api/v1/suppliers")
                                .param("q", "sup-find")
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(jsonPath("$.content[*].id", containsInAnyOrder(active, held)));
    }

    @Test
    void viewingASupplierDoesNotAllowEditingOne() throws Exception {
        mockMvc.perform(
                        post("/api/v1/suppliers")
                                .with(at(BRANCH, "purchase:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of("code", "SUP-NOPE", "name", "Nope Ltd"))))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "only the administrator adds a supplier; managing one, as a branch manager may, is not"
                    + " enough")
    void onlyTheAdministratorAddsASupplier() throws Exception {
        mockMvc.perform(
                        post("/api/v1/suppliers")
                                .with(at(BRANCH, "supplier:manage", "purchase:view"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of("code", "SUP-MGR", "name", "Managers Ltd"))))
                .andExpect(status().isForbidden());

        // Once the administrator has added it, managing it is the manager's business.
        String id = createSupplier("SUP-ADMIN");
        mockMvc.perform(
                        put("/api/v1/suppliers/" + id)
                                .with(at(BRANCH, "supplier:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "code",
                                                        "SUP-ADMIN",
                                                        "name",
                                                        "Renamed Ltd"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.name", is("Renamed Ltd")));
    }

    @Test
    @DisplayName("recording an invoice needs the supplier-invoice permission, not a purchasing one")
    void invoiceMatchingIsSeparatelyPermissioned() throws Exception {
        mockMvc.perform(get("/api/v1/supplier-invoices").with(at(BRANCH, "purchase:view")))
                .andExpect(status().isForbidden());

        mockMvc.perform(get("/api/v1/supplier-invoices").with(at(BRANCH, "supplier-invoice:view")))
                .andExpect(status().isOk());
    }

    // --- suppliers --------------------------------------------------------------

    @Test
    void createsListsAndSearchesSuppliers() throws Exception {
        String supplierId = createSupplier("SUP-LIST");

        mockMvc.perform(get("/api/v1/suppliers/" + supplierId).with(at(BRANCH, "purchase:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.code", is("SUP-LIST")))
                .andExpect(jsonPath("$.status", is("ACTIVE")))
                .andExpect(jsonPath("$.paymentTermsDays", is(30)));

        mockMvc.perform(
                        get("/api/v1/suppliers")
                                .param("q", "Wholesale")
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(greaterThanOrEqualTo(1))));

        mockMvc.perform(
                        put("/api/v1/suppliers/" + supplierId + "/status")
                                .with(at(BRANCH, "supplier:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"status\":\"ON_HOLD\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ON_HOLD")));
    }

    @Test
    void aDuplicateSupplierCodeIsRefused() throws Exception {
        createSupplier("SUP-DUP");

        mockMvc.perform(
                        post("/api/v1/suppliers")
                                .with(at(BRANCH, "supplier:create"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of("code", "SUP-DUP", "name", "Copycat Ltd"))))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("supplier.code_taken")));
    }

    @Test
    @DisplayName("a supplier price list is kept per product, with one preferred source")
    void managesASupplierPriceList() throws Exception {
        String supplierId = createSupplier("SUP-PRICE");

        mockMvc.perform(
                        post("/api/v1/suppliers/" + supplierId + "/products")
                                .with(at(BRANCH, "supplier:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "productId", FLOUR.toString(),
                                                        "sku", "FLOUR-2KG",
                                                        "productName", "Flour 2kg",
                                                        "agreedUnitCost", "95.00",
                                                        "minimumOrderQty", "24",
                                                        "preferred", true))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.agreedUnitCost", is(95.0)))
                .andExpect(jsonPath("$.preferred", is(true)))
                // Falls back to the supplier's lead time when the product has none of its own.
                .andExpect(jsonPath("$.effectiveLeadTimeDays", is(7)));

        mockMvc.perform(
                        get("/api/v1/suppliers/" + supplierId + "/products")
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku", is("FLOUR-2KG")));
    }

    // --- orders, receipts and invoices ------------------------------------------

    @Test
    @DisplayName("an order is raised, approved, sent, delivered and invoiced over HTTP")
    void theWholeFlowOverHttp() throws Exception {
        String supplierId = createSupplier("SUP-FLOW");
        String orderId = createOrder(supplierId);

        mockMvc.perform(
                        post("/api/v1/purchase-orders/" + orderId + "/submit")
                                .with(at(BRANCH, "purchase:create")))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/purchase-orders/" + orderId + "/approve")
                                .with(at(BRANCH, "purchase:approve")))
                .andExpect(status().isOk());
        mockMvc.perform(
                        post("/api/v1/purchase-orders/" + orderId + "/send")
                                .with(at(BRANCH, "purchase:create")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("SENT")));

        // Receive it, with freight to spread across the line.
        String grn =
                mockMvc.perform(
                                post("/api/v1/goods-receipts")
                                        .with(at(BRANCH, "purchase:receive"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                EventJson.write(
                                                        Map.of(
                                                                "supplierId",
                                                                supplierId,
                                                                "branchId",
                                                                BRANCH.toString(),
                                                                "purchaseOrderId",
                                                                orderId,
                                                                "deliveryNoteRef",
                                                                "DN-API-1",
                                                                "freightAmount",
                                                                "500.00",
                                                                "lines",
                                                                List.of(
                                                                        Map.of(
                                                                                "productId",
                                                                                        FLOUR
                                                                                                .toString(),
                                                                                "sku", "FLOUR-2KG",
                                                                                "quantityReceived",
                                                                                        "100",
                                                                                "unitCost", "95.00",
                                                                                "batchNumber",
                                                                                        "B-API-1",
                                                                                "expiryDate",
                                                                                        LocalDate
                                                                                                .now()
                                                                                                .plusMonths(
                                                                                                        6)
                                                                                                .toString()))))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status", is("DRAFT")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String grnId = EventJson.mapper().readTree(grn).get("id").asString();

        mockMvc.perform(
                        post("/api/v1/goods-receipts/" + grnId + "/post")
                                .with(at(BRANCH, "purchase:receive")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("POSTED")))
                .andExpect(jsonPath("$.goodsTotal", is(9500.0)))
                .andExpect(jsonPath("$.landedTotal", is(10000.0)))
                // 500 of freight on 100 units is 5.00 a unit on top of the 95.00 charged.
                .andExpect(jsonPath("$.lines[0].landedUnitCost", is(100.0)))
                .andExpect(jsonPath("$.lines[0].allocatedCharges", is(500.0)));

        mockMvc.perform(get("/api/v1/purchase-orders/" + orderId).with(at(BRANCH, "purchase:view")))
                .andExpect(jsonPath("$.status", is("RECEIVED")))
                .andExpect(jsonPath("$.lines[0].quantityOutstanding", is(0.0)));

        // The supplier bills 100.00 a unit against an agreed 95.00.
        mockMvc.perform(
                        post("/api/v1/supplier-invoices")
                                .with(at(BRANCH, "supplier-invoice:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "supplierId",
                                                        supplierId,
                                                        "invoiceNumber",
                                                        "INV-API-1",
                                                        "invoiceDate",
                                                        LocalDate.now().toString(),
                                                        "netAmount",
                                                        "10000.00",
                                                        "purchaseOrderId",
                                                        orderId,
                                                        "grnId",
                                                        grnId,
                                                        "lines",
                                                        List.of(
                                                                Map.of(
                                                                        "productId",
                                                                                FLOUR.toString(),
                                                                        "sku", "FLOUR-2KG",
                                                                        "quantity", "100",
                                                                        "unitCost", "100.00"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.matchStatus", is("EXCEPTION")))
                .andExpect(jsonPath("$.varianceAmount", is(500.0)))
                .andExpect(jsonPath("$.variances", hasSize(1)))
                .andExpect(jsonPath("$.variances[0].type", is("PRICE_VARIANCE")))
                .andExpect(jsonPath("$.variances[0].amountEffect", is(500.0)))
                .andExpect(jsonPath("$.justifiedTotal", is(9500.0)));

        // Read back in the unfiltered list. It once answered 500: that query had no fetch graph,
        // the response reads the supplier's name after the transaction has closed, and the list
        // test ran against an empty table.
        mockMvc.perform(get("/api/v1/supplier-invoices").with(at(BRANCH, "supplier-invoice:view")))
                .andExpect(status().isOk())
                .andExpect(
                        jsonPath(
                                "$.content[?(@.invoiceNumber == 'INV-API-1')].supplierName",
                                hasSize(1)));
    }

    @Test
    void anOrderWithNoLinesIsRejected() throws Exception {
        String supplierId = createSupplier("SUP-EMPTY");

        mockMvc.perform(
                        post("/api/v1/purchase-orders")
                                .with(at(BRANCH, "purchase:create"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "supplierId", supplierId,
                                                        "branchId", BRANCH.toString(),
                                                        "lines", List.of()))))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("request.validation_failed")));
    }

    @Test
    @DisplayName("an unknown enum value is answered with the values that would have worked")
    void anInvalidReturnReasonIsActionable() throws Exception {
        String supplierId = createSupplier("SUP-ENUM");

        // Asserted for both orderings, because Jackson records a property path in one and not the
        // other depending on what else is in the body - and neither may answer "could not be
        // parsed" and nothing else. Naming the field is a bonus; naming the accepted values is the
        // part a caller needs, so that is what is pinned here.
        for (String body :
                List.of(
                        """
                        {"reasonCode":"BECAUSE","supplierId":"%s","branchId":"%s",
                         "lines":[{"productId":"%s","quantity":"1","unitCost":"10.00"}]}
                        """
                                .formatted(supplierId, BRANCH, FLOUR),
                        """
                        {"supplierId":"%s","branchId":"%s",
                         "lines":[{"productId":"%s","quantity":"1","unitCost":"10.00"}],
                         "reasonCode":"BECAUSE"}
                        """
                                .formatted(supplierId, BRANCH, FLOUR))) {

            mockMvc.perform(
                            post("/api/v1/supplier-returns")
                                    .with(at(BRANCH, "purchase:receive"))
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isBadRequest())
                    .andExpect(jsonPath("$.code", is("request.malformed")))
                    .andExpect(jsonPath("$.detail", containsString("ReturnReason")))
                    .andExpect(jsonPath("$.detail", containsString("DAMAGED_IN_TRANSIT")))
                    // Never the submitted value: the next field shaped like this holds a password.
                    .andExpect(jsonPath("$.detail", not(containsString("BECAUSE"))));
        }
    }

    @Test
    @DisplayName("a return is priced from the receipt when no cost is given")
    void aReturnTakesItsCostFromTheReceipt() throws Exception {
        String supplierId = createSupplier("SUP-RET");
        String grnId = receiveWithoutAnOrder(supplierId);

        mockMvc.perform(
                        post("/api/v1/supplier-returns")
                                .with(at(BRANCH, "purchase:receive"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(
                                                Map.of(
                                                        "supplierId",
                                                        supplierId,
                                                        "branchId",
                                                        BRANCH.toString(),
                                                        "grnId",
                                                        grnId,
                                                        "reasonCode",
                                                        "DAMAGED_IN_TRANSIT",
                                                        "lines",
                                                        List.of(
                                                                Map.of(
                                                                        "productId",
                                                                                FLOUR.toString(),
                                                                        "sku", "FLOUR-2KG",
                                                                        "quantity", "5"))))))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.status", is("DRAFT")))
                .andExpect(jsonPath("$.reasonCode", is("DAMAGED_IN_TRANSIT")))
                // Landed cost, not the invoice price: 95.00 plus its share of the 500 freight.
                .andExpect(jsonPath("$.lines[0].unitCost", is(100.0)))
                .andExpect(jsonPath("$.totalAmount", is(500.0)));
    }

    @Test
    void anUnknownSupplierIsNotFound() throws Exception {
        mockMvc.perform(
                        get("/api/v1/suppliers/" + UUID.randomUUID())
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isNotFound())
                .andExpect(jsonPath("$.code", is("supplier.not_found")));
    }

    @Test
    void reorderSuggestionsAreReadablePerBranch() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reorder-suggestions")
                                .param("branchId", BRANCH.toString())
                                .with(at(BRANCH, "purchase:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(0)));
    }

    // --- helpers ----------------------------------------------------------------

    private String createSupplier(String code) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/suppliers")
                                        .with(at(BRANCH, "supplier:create"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                EventJson.write(
                                                        Map.of(
                                                                "code",
                                                                code,
                                                                "name",
                                                                "Wholesale Foods",
                                                                "email",
                                                                "orders@example.com",
                                                                "paymentTermsDays",
                                                                30,
                                                                "leadTimeDays",
                                                                7))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return EventJson.mapper().readTree(body).get("id").asString();
    }

    private String createOrder(String supplierId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/purchase-orders")
                                        .with(at(BRANCH, "purchase:create"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                EventJson.write(
                                                        Map.of(
                                                                "supplierId", supplierId,
                                                                "branchId", BRANCH.toString(),
                                                                "expectedDeliveryDate",
                                                                        LocalDate.now()
                                                                                .plusDays(7)
                                                                                .toString(),
                                                                "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "productId",
                                                                                                FLOUR
                                                                                                        .toString(),
                                                                                        "sku",
                                                                                                "FLOUR-2KG",
                                                                                        "productName",
                                                                                                "Flour 2kg",
                                                                                        "quantity",
                                                                                                "100",
                                                                                        "unitCost",
                                                                                                "95.00"))))))
                        .andExpect(status().isCreated())
                        .andExpect(jsonPath("$.status", is("DRAFT")))
                        .andExpect(jsonPath("$.orderNumber", containsString("PO-")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        return EventJson.mapper().readTree(body).get("id").asString();
    }

    private String receiveWithoutAnOrder(String supplierId) throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/goods-receipts")
                                        .with(at(BRANCH, "purchase:receive"))
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                EventJson.write(
                                                        Map.of(
                                                                "supplierId",
                                                                supplierId,
                                                                "branchId",
                                                                BRANCH.toString(),
                                                                "freightAmount",
                                                                "500.00",
                                                                "lines",
                                                                List.of(
                                                                        Map.of(
                                                                                "productId",
                                                                                        FLOUR
                                                                                                .toString(),
                                                                                "sku", "FLOUR-2KG",
                                                                                "quantityReceived",
                                                                                        "100",
                                                                                "unitCost", "95.00",
                                                                                "batchNumber",
                                                                                        "B-RET-1"))))))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();
        String grnId = EventJson.mapper().readTree(body).get("id").asString();

        mockMvc.perform(
                        post("/api/v1/goods-receipts/" + grnId + "/post")
                                .with(at(BRANCH, "purchase:receive")))
                .andExpect(status().isOk());
        return grnId;
    }
}
