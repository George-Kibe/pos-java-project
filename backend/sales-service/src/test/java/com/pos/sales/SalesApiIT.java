package com.pos.sales;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import com.pos.events.EventJson;
import com.pos.sales.domain.TillSession;
import com.pos.sales.service.TillSessionService;

/** The sales HTTP surface: who may do what, where, and what a retried request does. */
@AutoConfigureMockMvc
class SalesApiIT extends SalesTestBase {

    private static final UUID OTHER_BRANCH =
            UUID.fromString("018f3a1c-0000-7000-8000-00000000b002");

    @Autowired private MockMvc mockMvc;
    @Autowired private TillSessionService tills;

    /** A token for someone assigned to one branch, carrying exactly these permissions. */
    private static RequestPostProcessor at(UUID branch, UUID user, String... permissions) {
        GrantedAuthority[] authorities =
                Arrays.stream(permissions)
                        .map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new);

        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .claim("branches", List.of(branch.toString()))
                                .build())
                .authorities(authorities);
    }

    private static RequestPostProcessor cashier() {
        return at(BRANCH, CASHIER, CASHIER_PERMISSIONS);
    }

    // --- authorization ----------------------------------------------------------

    @Test
    void salesAreNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/sales").param("branchId", BRANCH.toString()))
                .andExpect(status().isUnauthorized());
        mockMvc.perform(post("/api/v1/till-sessions").contentType(MediaType.APPLICATION_JSON))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a cashier cannot void a sale; the refusal is a problem document")
    void voidingNeedsItsOwnPermission() throws Exception {
        mockMvc.perform(
                        post("/api/v1/sales/" + UUID.randomUUID() + "/void")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                // Valid, so the refusal is authorization and not binding.
                                .content(json(Map.of("reason", "Customer changed their mind"))))
                .andExpect(status().isForbidden())
                .andExpect(
                        header().string(
                                        HttpHeaders.CONTENT_TYPE,
                                        containsString("application/problem+json")));
    }

    @Test
    @DisplayName("a shift opens with 201 and a Location")
    void openingAShift() throws Exception {
        mockMvc.perform(
                        post("/api/v1/till-sessions")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(openTill(BRANCH)))
                .andExpect(status().isCreated())
                .andExpect(header().string(HttpHeaders.LOCATION, containsString("/till-sessions/")))
                .andExpect(jsonPath("$.status", is("OPEN")));
    }

    @Test
    void aCashierCannotOpenAShiftAtAnotherBranch() throws Exception {
        mockMvc.perform(
                        post("/api/v1/till-sessions")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(openTill(OTHER_BRANCH)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("branch.access_denied")));

        assertThat(count("till_sessions")).isZero();
    }

    @Test
    @DisplayName("a basket on another branch's till is refused before anything is written")
    void aCartIsBranchCheckedBeforeItExists() throws Exception {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));

        mockMvc.perform(
                        post("/api/v1/carts")
                                .with(at(OTHER_BRANCH, UUID.randomUUID(), CASHIER_PERMISSIONS))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("tillSessionId", till.getId()))))
                .andExpect(status().isForbidden());

        assertThat(count("carts")).isZero();
    }

    // --- retries ----------------------------------------------------------------

    @Test
    @DisplayName("a retried request with the same Idempotency-Key gets the first answer")
    void aRetriedRequestIsReplayed() throws Exception {
        String body = openTill(BRANCH);

        String first =
                mockMvc.perform(
                                post("/api/v1/till-sessions")
                                        .with(cashier())
                                        .header("Idempotency-Key", "open-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Without the key this would be a 409: the register already has a shift open.
        String retried =
                mockMvc.perform(
                                post("/api/v1/till-sessions")
                                        .with(cashier())
                                        .header("Idempotency-Key", "open-1")
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(body))
                        .andExpect(status().isCreated())
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        assertThat(retried).isEqualTo(first);
        assertThat(count("till_sessions")).isEqualTo(1);
    }

    @Test
    @DisplayName("an offline batch over HTTP needs a key, and replays under it")
    void offlineSyncOverHttp() throws Exception {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        String body =
                json(
                        Map.of(
                                "branchId",
                                BRANCH,
                                "registerId",
                                REGISTER,
                                "sales",
                                List.of(
                                        Map.of(
                                                "clientSaleId",
                                                UUID.randomUUID(),
                                                "tillSessionId",
                                                till.getId(),
                                                "paymentMethod",
                                                "CASH",
                                                "claimedGrandTotal",
                                                "116.00",
                                                "lines",
                                                List.of(
                                                        Map.of(
                                                                "productId",
                                                                SOAP.id(),
                                                                "sku",
                                                                SOAP.sku(),
                                                                "quantity",
                                                                "1"))))));

        mockMvc.perform(
                        post("/api/v1/sales/sync")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("sync.idempotency_key_required")));

        for (boolean replayed : new boolean[] {false, true}) {
            mockMvc.perform(
                            post("/api/v1/sales/sync")
                                    .with(cashier())
                                    .header("Idempotency-Key", "terminal-7-batch-1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.replayed", is(replayed)))
                    .andExpect(jsonPath("$.accepted", is(1)))
                    .andExpect(jsonPath("$.results[0].receiptNumber", is("R-000001")));
        }
        assertThat(count("sales")).isEqualTo(1);
    }

    // --- the lane, end to end ---------------------------------------------------

    @Test
    @DisplayName("a shift, a sale, a receipt, a return and a close, all over HTTP")
    void theLaneOverHttp() throws Exception {
        // Every response here is mapped from entities after the transaction has ended, with
        // open-in-view off - so this walk is what proves no mapper touches a lazy association.
        String tillId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/till-sessions")
                                                .with(cashier())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(openTill(BRANCH)))
                                .andExpect(status().isCreated()),
                        "$.id");

        String cartId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/carts")
                                                .with(cashier())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json(Map.of("tillSessionId", tillId))))
                                .andExpect(status().isCreated()),
                        "$.id");

        String soapLine =
                read(
                        mockMvc.perform(
                                        post("/api/v1/carts/" + cartId + "/lines")
                                                .with(cashier())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json(
                                                                Map.of(
                                                                        "productId",
                                                                        SOAP.id(),
                                                                        "quantity",
                                                                        "1"))))
                                .andExpect(status().isOk()),
                        "$.lines[0].id");
        mockMvc.perform(
                        put("/api/v1/carts/" + cartId + "/lines/" + soapLine + "/quantity")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("quantity", "3"))))
                .andExpect(status().isOk());
        String flourLine =
                read(
                        mockMvc.perform(
                                        post("/api/v1/carts/" + cartId + "/lines")
                                                .with(cashier())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json(
                                                                Map.of(
                                                                        "productId",
                                                                        FLOUR.id(),
                                                                        "quantity",
                                                                        "1"))))
                                .andExpect(status().isOk()),
                        "$.lines[1].id");
        mockMvc.perform(
                        post("/api/v1/carts/" + cartId + "/lines/" + flourLine + "/void")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("reason", "Customer put it back"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/carts/" + cartId).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines.length()", is(2)));

        String saleId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/sales/checkout")
                                                .with(cashier())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(json(Map.of("cartId", cartId))))
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status", is("PENDING"))),
                        "$.id");

        String saleLine =
                read(
                        mockMvc.perform(
                                        post("/api/v1/sales/" + saleId + "/tender")
                                                .with(cashier())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json(
                                                                Map.of(
                                                                        "tenders",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "method",
                                                                                        "CASH",
                                                                                        "amount",
                                                                                        "400.00")),
                                                                        "amountTendered",
                                                                        "400.00"))))
                                .andExpect(status().isOk())
                                .andExpect(jsonPath("$.status", is("PAID")))
                                .andExpect(jsonPath("$.receiptNumber", is("R-000001"))),
                        "$.lines[0].id");

        mockMvc.perform(get("/api/v1/sales/" + saleId).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.payments.length()", is(1)));
        mockMvc.perform(get("/api/v1/sales/receipt/R-000001").with(cashier()))
                .andExpect(status().isOk());
        String receiptId =
                read(
                        mockMvc.perform(
                                        get("/api/v1/sales/" + saleId + "/receipts")
                                                .with(cashier()))
                                .andExpect(status().isOk()),
                        "$[0].id");
        mockMvc.perform(post("/api/v1/sales/receipts/" + receiptId + "/reprint").with(cashier()))
                .andExpect(status().isOk());

        RequestPostProcessor supervisor = at(BRANCH, SUPERVISOR, SUPERVISOR_PERMISSIONS);
        mockMvc.perform(get("/api/v1/returns/eligibility").param("saleId", saleId).with(supervisor))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].eligible", is(true)));
        String returnId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/returns")
                                                .with(supervisor)
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json(
                                                                Map.of(
                                                                        "saleId",
                                                                        saleId,
                                                                        "reason",
                                                                        "FAULTY",
                                                                        "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "saleLineId",
                                                                                        saleLine,
                                                                                        "quantity",
                                                                                        "1",
                                                                                        "resaleable",
                                                                                        false))))))
                                .andExpect(status().isCreated())
                                .andExpect(header().exists(HttpHeaders.LOCATION)),
                        "$.id");
        mockMvc.perform(get("/api/v1/returns/" + returnId).with(supervisor))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/api/v1/returns")
                                .param("branchId", BRANCH.toString())
                                .with(supervisor))
                .andExpect(status().isOk());

        mockMvc.perform(
                        post("/api/v1/till-sessions/" + tillId + "/drops")
                                .with(supervisor)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("amount", "1000.00", "reason", "Safe drop"))))
                .andExpect(status().isOk());
        mockMvc.perform(get("/api/v1/sales").param("branchId", BRANCH.toString()).with(supervisor))
                .andExpect(status().isOk());
        mockMvc.perform(
                        get("/api/v1/till-sessions/registers/" + REGISTER + "/current")
                                .with(cashier()))
                .andExpect(status().isOk());

        mockMvc.perform(post("/api/v1/till-sessions/" + tillId + "/begin-close").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSING")));
        mockMvc.perform(
                        post("/api/v1/till-sessions/" + tillId + "/close")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("countedCash", "4232.00"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("CLOSED")));
        mockMvc.perform(get("/api/v1/till-sessions/" + tillId + "/z-report").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.countersAgreeWithSales", is(true)))
                .andExpect(jsonPath("$.variance", is(0.0)));
        mockMvc.perform(get("/api/v1/till-sessions/" + tillId + "/cash-movements").with(cashier()))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a basket can be parked and recalled over HTTP, and abandoned")
    void parkingABasketOverHttp() throws Exception {
        TillSession till = tills.open(BRANCH, REGISTER, money("1000.00"));
        String cartId =
                read(
                        mockMvc.perform(
                                        post("/api/v1/carts")
                                                .with(cashier())
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        json(
                                                                Map.of(
                                                                        "tillSessionId",
                                                                        till.getId()))))
                                .andExpect(status().isCreated()),
                        "$.id");
        mockMvc.perform(
                        put("/api/v1/carts/" + cartId + "/customer")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        json(
                                                Map.of(
                                                        "customerId",
                                                        UUID.randomUUID(),
                                                        "member",
                                                        true))))
                .andExpect(status().isOk());

        String code =
                read(
                        mockMvc.perform(
                                        post("/api/v1/carts/" + cartId + "/suspend")
                                                .with(cashier()))
                                .andExpect(status().isOk()),
                        "$.suspendCode");
        mockMvc.perform(
                        get("/api/v1/carts/suspended")
                                .param("branchId", BRANCH.toString())
                                .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.length()", is(1)));
        mockMvc.perform(
                        post("/api/v1/carts/recall")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(json(Map.of("branchId", BRANCH, "code", code))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("OPEN")));
        mockMvc.perform(post("/api/v1/carts/" + cartId + "/abandon").with(cashier()))
                .andExpect(status().isOk());
    }

    // --- helpers ----------------------------------------------------------------

    private static String openTill(UUID branch) {
        return json(Map.of("branchId", branch, "registerId", REGISTER, "openingFloat", "5000.00"));
    }

    private static String read(ResultActions result, String path) throws Exception {
        Object value = JsonPath.read(result.andReturn().getResponse().getContentAsString(), path);
        return String.valueOf(value);
    }

    private static String json(Object value) {
        return EventJson.write(value);
    }

    private long count(String table) {
        Long count = jdbc.sql("SELECT count(*) FROM sales." + table).query(Long.class).single();
        return count == null ? 0 : count;
    }
}
