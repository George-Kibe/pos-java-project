package com.pos.purchasing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.time.LocalDate;
import java.util.Arrays;
import java.util.HashMap;
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
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.pos.events.EventJson;
import com.pos.events.Topics;

import tools.jackson.databind.JsonNode;

/** The expenses register: the approval limit, a second person, voids that keep the record. */
@AutoConfigureMockMvc
@DisplayName("Expenses")
class ExpenseIT extends PurchasingTestBase {

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();
    private static final UUID MANAGER = UUID.randomUUID();
    private static final UUID OTHER_MANAGER = UUID.randomUUID();
    private static final String[] MANAGES = {"expense:record", "expense:approve", "expense:view"};

    @Autowired private MockMvc mockMvc;

    private static RequestPostProcessor as(UUID user, UUID branch, String... permissions) {
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .claim("branches", List.of(branch.toString()))
                                .build())
                .authorities(
                        Arrays.stream(permissions)
                                .map(SimpleGrantedAuthority::new)
                                .toArray(GrantedAuthority[]::new));
    }

    private ResultActions record(RequestPostProcessor who, UUID branch, String amount)
            throws Exception {
        Map<String, Object> body = new HashMap<>();
        if (branch != null) {
            body.put("branchId", branch);
        }
        body.put("category", "ELECTRICITY");
        body.put("description", "Power, September");
        body.put("payee", "Kenya Power");
        body.put("incurredOn", LocalDate.now().toString());
        body.put("amount", amount);
        body.put("taxAmount", "0");
        return mockMvc.perform(
                post("/api/v1/expenses")
                        .with(who)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(EventJson.write(body)));
    }

    private static String id(ResultActions result) throws Exception {
        JsonNode node =
                EventJson.mapper().readTree(result.andReturn().getResponse().getContentAsString());
        return node.get("id").asString();
    }

    private List<Long> revisions(String expenseId) {
        return jdbc
                .sql(
                        "SELECT payload FROM purchasing.outbox WHERE topic = ? AND aggregate_id = ?"
                                + " ORDER BY created_at")
                .param(Topics.PURCHASING_EXPENSE_CHANGED)
                .param(UUID.fromString(expenseId))
                .query(String.class)
                .list()
                .stream()
                .map(
                        json ->
                                EventJson.mapper()
                                        .readTree(json)
                                        .get("payload")
                                        .get("revision")
                                        .asLong())
                .toList();
    }

    @Test
    @DisplayName("up to the limit an expense counts at once, and says so to reporting")
    void smallExpensesCountAtOnce() throws Exception {
        String expense =
                id(
                        record(as(MANAGER, BRANCH, MANAGES), BRANCH, "4500")
                                .andExpect(status().isCreated())
                                .andExpect(jsonPath("$.status", is("APPROVED")))
                                .andExpect(jsonPath("$.needsApproval", is(false)))
                                .andExpect(jsonPath("$.expenseNumber").exists()));
        assertThat(revisions(expense)).hasSize(1);
    }

    @Test
    @DisplayName(
            "above the limit it waits for someone other than whoever recorded it, and each change"
                    + " is a later revision")
    void largeExpensesNeedASecondPerson() throws Exception {
        String expense =
                id(
                        record(as(MANAGER, BRANCH, MANAGES), BRANCH, "25000")
                                .andExpect(jsonPath("$.status", is("PENDING_APPROVAL"))));

        mockMvc.perform(
                        post("/api/v1/expenses/" + expense + "/approve")
                                .with(as(MANAGER, BRANCH, MANAGES)))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("expense.approver_is_recorder")));
        mockMvc.perform(
                        post("/api/v1/expenses/" + expense + "/approve")
                                .with(as(OTHER_MANAGER, OTHER_BRANCH, MANAGES)))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/expenses/" + expense + "/approve")
                                .with(as(OTHER_MANAGER, BRANCH, MANAGES)))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("APPROVED")))
                .andExpect(jsonPath("$.decidedBy", is(OTHER_MANAGER.toString())));

        List<Long> revisions = revisions(expense);
        assertThat(revisions).hasSize(2);
        assertThat(revisions.get(1)).isGreaterThan(revisions.get(0));
    }

    @Test
    @DisplayName("a refusal and a void each need a reason, and the expense is kept")
    void refusedAndVoidedAreKept() throws Exception {
        String large = id(record(as(MANAGER, BRANCH, MANAGES), BRANCH, "25000"));
        mockMvc.perform(
                        post("/api/v1/expenses/" + large + "/reject")
                                .with(as(OTHER_MANAGER, BRANCH, MANAGES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("reason", " "))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/v1/expenses/" + large + "/reject")
                                .with(as(OTHER_MANAGER, BRANCH, MANAGES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("reason", "Not ours"))))
                .andExpect(jsonPath("$.status", is("REJECTED")));

        String small = id(record(as(MANAGER, BRANCH, MANAGES), BRANCH, "300"));
        mockMvc.perform(
                        post("/api/v1/expenses/" + small + "/void")
                                .with(as(MANAGER, BRANCH, MANAGES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("reason", "Keyed twice"))))
                .andExpect(jsonPath("$.status", is("VOIDED")))
                .andExpect(jsonPath("$.reason", is("Keyed twice")));
        mockMvc.perform(
                        post("/api/v1/expenses/" + small + "/void")
                                .with(as(MANAGER, BRANCH, MANAGES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("reason", "Again"))))
                .andExpect(status().isConflict());

        String today = LocalDate.now().toString();
        mockMvc.perform(
                        get("/api/v1/expenses")
                                .param("branchId", BRANCH.toString())
                                .param("from", today)
                                .param("to", today)
                                .with(as(MANAGER, BRANCH, "expense:view")))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
        mockMvc.perform(
                        get("/api/v1/expenses")
                                .param("branchId", BRANCH.toString())
                                .param("from", today)
                                .param("to", today)
                                .param("status", "VOIDED")
                                .with(as(MANAGER, BRANCH, "expense:view")))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("head office's expenses are the administrator's alone")
    void headOffice() throws Exception {
        record(as(MANAGER, BRANCH, MANAGES), null, "1000").andExpect(status().isForbidden());
        record(as(MANAGER, BRANCH, "expense:record", "expense:head-office"), null, "1000")
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.branchId").doesNotExist());
        String today = LocalDate.now().toString();
        mockMvc.perform(
                        get("/api/v1/expenses")
                                .param("from", today)
                                .param("to", today)
                                .with(as(MANAGER, BRANCH, "expense:view")))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/expenses")
                                .param("from", today)
                                .param("to", today)
                                .with(as(MANAGER, BRANCH, "expense:view", "expense:head-office")))
                .andExpect(jsonPath("$.content", hasSize(1)));
        record(as(MANAGER, BRANCH, MANAGES), OTHER_BRANCH, "1000")
                .andExpect(status().isForbidden());
    }

    @Test
    @DisplayName("the approval limit is a setting only settings:manage changes")
    void approvalLimit() throws Exception {
        mockMvc.perform(get("/api/v1/expense-settings").with(as(MANAGER, BRANCH, "expense:view")))
                .andExpect(jsonPath("$.approvalLimit", is(10000.0)));
        mockMvc.perform(
                        put("/api/v1/expense-settings")
                                .with(as(MANAGER, BRANCH, MANAGES))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("approvalLimit", 100))))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        put("/api/v1/expense-settings")
                                .with(as(MANAGER, BRANCH, "settings:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("approvalLimit", 100))))
                .andExpect(jsonPath("$.approvalLimit", is(100.0)));
        record(as(MANAGER, BRANCH, MANAGES), BRANCH, "150")
                .andExpect(jsonPath("$.status", is("PENDING_APPROVAL")));
    }
}
