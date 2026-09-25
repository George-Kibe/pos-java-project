package com.pos.inventory;

import static org.hamcrest.Matchers.hasItem;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
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

/**
 * What the back office reads and does at a branch: near-expiry stock, transfers and counts - and
 * that each action is taken by someone at the branch it belongs to.
 */
@AutoConfigureMockMvc
@DisplayName("Inventory in the back office")
class InventoryBackOfficeIT extends InventoryTestBase {

    private static final UUID HERE = UUID.randomUUID();
    private static final UUID THERE = UUID.randomUUID();
    private static final UUID ELSEWHERE = UUID.randomUUID();
    private static final String[] STOCK_ROLE = {
        "inventory:view", "inventory:adjust", "stocktake:manage", "transfer:manage"
    };

    @Autowired private MockMvc mockMvc;
    @Autowired private StockService stock;

    private static RequestPostProcessor at(UUID branch) {
        UUID user = UUID.randomUUID();
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(STOCK_ROLE))
                                .claim("branches", List.of(branch.toString()))
                                .build())
                .authorities(
                        Arrays.stream(STOCK_ROLE)
                                .map(SimpleGrantedAuthority::new)
                                .toArray(GrantedAuthority[]::new));
    }

    private UUID received(UUID branch, String sku, String quantity, LocalDate expiry) {
        UUID product = UUID.randomUUID();
        stock.receive(
                branch,
                UUID.randomUUID(),
                "TestSetup",
                List.of(
                        new StockService.ReceiptLine(
                                product,
                                sku,
                                new BigDecimal(quantity),
                                sku + "-B1",
                                expiry,
                                new BigDecimal("40.00"),
                                "KES")));
        return product;
    }

    private String id(org.springframework.test.web.servlet.ResultActions result) throws Exception {
        return EventJson.mapper()
                .readTree(result.andReturn().getResponse().getContentAsString())
                .get("id")
                .asString();
    }

    @Test
    @DisplayName("near-expiry stock is listed soonest first with its value; not to another branch")
    void nearExpiryStock() throws Exception {
        received(
                HERE,
                "YOG-" + UUID.randomUUID().toString().substring(0, 6),
                "6",
                LocalDate.now(java.time.ZoneId.of("Africa/Nairobi")).plusDays(5));
        received(
                HERE,
                "RICE-" + UUID.randomUUID().toString().substring(0, 6),
                "9",
                LocalDate.now().plusMonths(6));

        mockMvc.perform(
                        get("/api/v1/stock/expiring")
                                .param("branchId", HERE.toString())
                                .param("days", "30")
                                .with(at(HERE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].daysLeft", is(5)))
                .andExpect(jsonPath("$[0].value").value(240.0));
        mockMvc.perform(
                        get("/api/v1/stock/expiring")
                                .param("branchId", HERE.toString())
                                .with(at(ELSEWHERE)))
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName(
            "a transfer is sent by its sender and received by its receiver; both ends see it,"
                    + " nobody else does")
    void transfersBelongToTheirEnds() throws Exception {
        UUID product =
                received(
                        HERE,
                        "TR-" + UUID.randomUUID().toString().substring(0, 6),
                        "10",
                        LocalDate.now().plusMonths(3));
        String transfer =
                id(
                        mockMvc.perform(
                                        post("/api/v1/transfers")
                                                .with(at(HERE))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        EventJson.write(
                                                                Map.of(
                                                                        "reference",
                                                                        "TR-"
                                                                                + UUID.randomUUID()
                                                                                        .toString()
                                                                                        .substring(
                                                                                                0,
                                                                                                8),
                                                                        "fromBranchId",
                                                                        HERE,
                                                                        "toBranchId",
                                                                        THERE,
                                                                        "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "productId",
                                                                                        product,
                                                                                        "quantity",
                                                                                        4))))))
                                .andExpect(status().isCreated()));

        mockMvc.perform(
                        get("/api/v1/transfers")
                                .param("branchId", THERE.toString())
                                .with(at(THERE)))
                .andExpect(jsonPath("$.content[*].id", hasItem(transfer)));
        mockMvc.perform(get("/api/v1/transfers/" + transfer).with(at(THERE)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.lines", hasSize(1)));
        mockMvc.perform(get("/api/v1/transfers/" + transfer).with(at(ELSEWHERE)))
                .andExpect(status().isForbidden());

        // The receiver cannot send it; the sender cannot receive it.
        mockMvc.perform(post("/api/v1/transfers/" + transfer + "/dispatch").with(at(THERE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/transfers/" + transfer + "/dispatch").with(at(HERE)))
                .andExpect(jsonPath("$.status", is("IN_TRANSIT")));
        mockMvc.perform(post("/api/v1/transfers/" + transfer + "/receive").with(at(HERE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/transfers/" + transfer + "/receive").with(at(THERE)))
                .andExpect(jsonPath("$.status", is("RECEIVED")));
    }

    @Test
    @DisplayName("counts are listed per branch, abandoned when need be, and kept to their branch")
    void stockTakesStayAtTheirBranch() throws Exception {
        received(
                HERE,
                "ST-" + UUID.randomUUID().toString().substring(0, 6),
                "3",
                LocalDate.now().plusMonths(3));
        String count =
                id(
                        mockMvc.perform(
                                        post("/api/v1/stock-takes")
                                                .with(at(HERE))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        EventJson.write(
                                                                Map.of(
                                                                        "reference",
                                                                        "ST-"
                                                                                + UUID.randomUUID()
                                                                                        .toString()
                                                                                        .substring(
                                                                                                0,
                                                                                                8),
                                                                        "branchId",
                                                                        HERE))))
                                .andExpect(status().isCreated()));
        mockMvc.perform(
                        get("/api/v1/stock-takes")
                                .param("branchId", HERE.toString())
                                .with(at(HERE)))
                .andExpect(jsonPath("$.content[*].id", hasItem(count)));
        mockMvc.perform(get("/api/v1/stock-takes/" + count).with(at(ELSEWHERE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/stock-takes/" + count + "/cancel").with(at(ELSEWHERE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/stock-takes/" + count + "/cancel").with(at(HERE)))
                .andExpect(jsonPath("$.status", is("CANCELLED")));
    }

    @Test
    @DisplayName("an adjustment is posted by someone at its branch")
    void adjustmentsArePostedAtTheirBranch() throws Exception {
        UUID product =
                received(
                        HERE,
                        "ADJ-" + UUID.randomUUID().toString().substring(0, 6),
                        "5",
                        LocalDate.now().plusMonths(3));
        String adjustment =
                id(
                        mockMvc.perform(
                                        post("/api/v1/adjustments")
                                                .with(at(HERE))
                                                .contentType(MediaType.APPLICATION_JSON)
                                                .content(
                                                        EventJson.write(
                                                                Map.of(
                                                                        "branchId",
                                                                        HERE,
                                                                        "reasonCode",
                                                                        "DAMAGE",
                                                                        "lines",
                                                                        List.of(
                                                                                Map.of(
                                                                                        "productId",
                                                                                        product,
                                                                                        "quantityDelta",
                                                                                        -1))))))
                                .andExpect(status().isCreated()));
        mockMvc.perform(post("/api/v1/adjustments/" + adjustment + "/post").with(at(ELSEWHERE)))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/adjustments/" + adjustment + "/post").with(at(HERE)))
                .andExpect(status().isOk());
    }
}
