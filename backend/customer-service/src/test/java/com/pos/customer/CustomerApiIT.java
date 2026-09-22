package com.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.notNullValue;
import static org.hamcrest.Matchers.nullValue;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.jayway.jsonpath.JsonPath;

import com.pos.customer.domain.Customer;
import com.pos.customer.domain.CustomerStatus;
import com.pos.customer.domain.LoyaltyTransactionType;
import com.pos.events.Topics;

/** Enrolling, finding, and the things a member may ask the shop to do with their data. */
class CustomerApiIT extends CustomerTestBase {

    @Test
    @DisplayName("a member is enrolled with a loyalty account already open")
    void enrollingOpensAnAccount() throws Exception {
        String body =
                mockMvc.perform(
                                post("/api/v1/customers")
                                        .with(manager())
                                        .contentType(MediaType.APPLICATION_JSON)
                                        .content(
                                                """
                                                {"firstName":"Njeri","lastName":"Kamau",
                                                 "phone":"0722 111 222","email":"njeri@example.com"}"""))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.customerNumber", is("C-000001")))
                        // Stored in one shape, however it was typed.
                        .andExpect(jsonPath("$.phone", is("254722111222")))
                        .andExpect(jsonPath("$.displayName", is("Njeri Kamau")))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        String id = JsonPath.read(body, "$.id");
        mockMvc.perform(get("/api/v1/loyalty/accounts/" + id).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.pointsBalance", is(0)))
                .andExpect(jsonPath("$.tier.code", is("BRONZE")));
    }

    @Test
    @DisplayName(
            "a lane finds a member by phone however it is typed, by card, or by part of a name")
    void lookupFindsAMemberEveryWayALaneWouldTry() throws Exception {
        Customer member = enrol("Wanjiku", "0733444555");
        customers.update(
                member.getId(),
                new com.pos.customer.service.CustomerService.Details(
                        "Wanjiku", "Wanjiru", "0733444555", null, null, "CARD-99", null, null));

        for (String typed : new String[] {"0733444555", "+254733444555", "254733444555"}) {
            mockMvc.perform(get("/api/v1/customers").param("q", typed).with(cashier()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$.content", hasSize(1)))
                    .andExpect(jsonPath("$.content[0].id", is(member.getId().toString())));
        }
        mockMvc.perform(get("/api/v1/customers").param("q", "CARD-99").with(cashier()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        // Part of a name, not only its start: the trigram index earns its keep here.
        mockMvc.perform(get("/api/v1/customers").param("q", "anjir").with(cashier()))
                .andExpect(jsonPath("$.content", hasSize(1)));
        mockMvc.perform(get("/api/v1/customers").param("q", "C-000001").with(cashier()))
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    @DisplayName("one person cannot become two members on the same number")
    void aDuplicatePhoneIsRefusedWithTheMemberItBelongsTo() throws Exception {
        enrol("Otieno", "0744555666");

        mockMvc.perform(
                        post("/api/v1/customers")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstName\":\"Someone\",\"phone\":\"0744555666\"}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code", is("customer.phone_taken")))
                .andExpect(
                        jsonPath("$.detail", is("Member C-000001 already uses that phone number")));
    }

    @Test
    void aNumberThatIsNotAKenyanMobileIsRefused() throws Exception {
        mockMvc.perform(
                        post("/api/v1/customers")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstName\":\"Someone\",\"phone\":\"12345\"}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("customer.invalid_phone")));
    }

    @Test
    @DisplayName("consent is a history, not a flag: both the granting and the withdrawal are kept")
    void consentIsRecordedAsHistory() throws Exception {
        Customer member = enrol("Peter", "0755666777");

        mockMvc.perform(
                        post("/api/v1/customers/" + member.getId() + "/consents")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"channel\":\"MARKETING_SMS\",\"granted\":true,"
                                                + "\"source\":\"TILL\",\"note\":\"Asked at the lane\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.granted", is(true)));
        mockMvc.perform(
                        post("/api/v1/customers/" + member.getId() + "/consents")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"channel\":\"MARKETING_SMS\",\"granted\":false}"))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/customers/" + member.getId() + "/consents").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                // Newest first: the current answer is no.
                .andExpect(jsonPath("$[0].granted", is(false)))
                .andExpect(jsonPath("$[1].granted", is(true)));
    }

    @Test
    void anAddressIsKeptAndOnlyOneIsTheDefault() throws Exception {
        Customer member = enrol("Quincy", "0766777888");
        for (String line : new String[] {"House 4, Kileleshwa", "Shop 9, Westlands"}) {
            mockMvc.perform(
                            post("/api/v1/customers/" + member.getId() + "/addresses")
                                    .with(manager())
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(
                                            "{\"line1\":\"%s\",\"town\":\"Nairobi\",\"makeDefault\":true}"
                                                    .formatted(line)))
                    .andExpect(status().isCreated());
        }

        mockMvc.perform(get("/api/v1/customers/" + member.getId() + "/addresses").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)))
                .andExpect(jsonPath("$[0].isDefault", is(false)))
                .andExpect(jsonPath("$[1].isDefault", is(true)));
    }

    @Test
    @DisplayName("a member can be handed everything held about them")
    void exportGivesTheMemberTheirData() throws Exception {
        Customer member = enrol("Rose", "0777888999");
        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), saleId, "3000.00"),
                saleId);

        mockMvc.perform(get("/api/v1/customers/" + member.getId() + "/export").with(manager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.customer.phone", is("254777888999")))
                .andExpect(jsonPath("$.loyalty.pointsBalance", is(30)))
                .andExpect(jsonPath("$.loyalty.pointsValue", is(30)))
                .andExpect(jsonPath("$.transactions", hasSize(1)))
                .andExpect(jsonPath("$.transactions[0].type", is("ACCRUAL")))
                .andExpect(jsonPath("$.generatedAt", notNullValue()));
    }

    @Test
    @DisplayName("erasure forgets the person, keeps the ledger, and frees the number")
    void erasureRemovesThePersonNotTheAccounts() throws Exception {
        Customer member = enrol("Susan", "0788999000");
        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), saleId, "3000.00"),
                saleId);

        mockMvc.perform(
                        post("/api/v1/customers/" + member.getId() + "/erasure")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Asked to be forgotten\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ERASED")))
                .andExpect(jsonPath("$.phone", nullValue()))
                .andExpect(jsonPath("$.firstName", nullValue()));

        // The ledger stays - it is a financial record - and what was owed is written off.
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isZero();
        assertThat(loyalty.allFor(member.getId()))
                .anyMatch(t -> t.getType() == LoyaltyTransactionType.ADJUSTMENT)
                .anyMatch(t -> t.getType() == LoyaltyTransactionType.ACCRUAL);
        assertThat(customers.require(member.getId()).getStatus()).isEqualTo(CustomerStatus.ERASED);

        // The number belongs to whoever holds it now.
        mockMvc.perform(
                        post("/api/v1/customers")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstName\":\"New\",\"phone\":\"0788999000\"}"))
                .andExpect(status().isCreated());
        // And an erased record is not edited back into existence.
        mockMvc.perform(
                        put("/api/v1/customers/" + member.getId())
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstName\":\"Susan\"}"))
                .andExpect(status().isConflict());
    }

    // --- loyalty by hand ------------------------------------------------------------

    @Test
    @DisplayName("a manual adjustment is recorded with who did it and why")
    void anAdjustmentIsAudited() throws Exception {
        Customer member = enrol("Tabitha", "0799000111");

        mockMvc.perform(
                        post("/api/v1/loyalty/adjustments")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        """
                                        {"customerId":"%s","points":250,
                                         "reason":"Goodwill after a long queue"}"""
                                                .formatted(member.getId())))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.points", is(250)))
                .andExpect(jsonPath("$.balanceAfter", is(250)))
                .andExpect(jsonPath("$.reason", is("Goodwill after a long queue")));

        mockMvc.perform(
                        get("/api/v1/loyalty/accounts/" + member.getId() + "/transactions")
                                .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content[0].type", is("ADJUSTMENT")));

        // More than the member has cannot be taken away.
        mockMvc.perform(
                        post("/api/v1/loyalty/adjustments")
                                .with(manager())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"customerId\":\"%s\",\"points\":-500,\"reason\":\"Oops\"}"
                                                .formatted(member.getId())))
                .andExpect(status().isUnprocessableContent())
                .andExpect(jsonPath("$.code", is("loyalty.insufficient_points")));
    }

    @Test
    void theTierLadderIsReadable() throws Exception {
        mockMvc.perform(get("/api/v1/loyalty/tiers").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(3)))
                .andExpect(jsonPath("$[0].code", is("BRONZE")))
                .andExpect(jsonPath("$[2].pointsMultiplier", is(1.5)));
    }

    // --- authorization ---------------------------------------------------------------

    @Test
    void customersAreNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/customers")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/loyalty/tiers")).andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a cashier may look a member up but not change one, and may not adjust points")
    void viewingIsNotManaging() throws Exception {
        Customer member = enrol("Umar", "0700111222");

        mockMvc.perform(get("/api/v1/customers/" + member.getId()).with(cashier()))
                .andExpect(status().isOk());
        mockMvc.perform(
                        put("/api/v1/customers/" + member.getId())
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"firstName\":\"Umar\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/loyalty/adjustments")
                                .with(at(CASHIER, "customer:view", "customer:manage"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"customerId\":\"%s\",\"points\":100,\"reason\":\"No\"}"
                                                .formatted(member.getId())))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/customers/" + member.getId() + "/export").with(cashier()))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownMemberIsA404() throws Exception {
        mockMvc.perform(get("/api/v1/customers/" + UUID.randomUUID()).with(cashier()))
                .andExpect(status().isNotFound());
    }
}
