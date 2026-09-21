package com.pos.common.web;

import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.hamcrest.Matchers.matchesPattern;
import static org.hamcrest.Matchers.not;
import static org.hamcrest.Matchers.notNullValue;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.test.web.servlet.MockMvc;

import com.pos.common.correlation.CorrelationId;
import com.pos.common.testapp.TestApplication;

/** The error and security contract every service inherits from common-lib. */
@SpringBootTest(classes = TestApplication.class)
@AutoConfigureMockMvc
class ErrorContractTest {

    @Autowired private MockMvc mockMvc;

    private static final UUID USER = UUID.randomUUID();
    private static final UUID HOME_BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();

    private static org.springframework.test.web.servlet.request.RequestPostProcessor cashier() {
        return jwt().jwt(
                        TestApplication.jwtWith(USER)
                                .claim("perms", List.of("sale:create"))
                                .claim("branches", List.of(HOME_BRANCH.toString()))
                                .build())
                .authorities(new SimpleGrantedAuthority("sale:create"));
    }

    // --- authentication -------------------------------------------------------

    @Test
    @DisplayName("an unauthenticated call is rejected as problem+json, not Spring's bare 401")
    void unauthenticatedRequestReturnsProblemJson() throws Exception {
        mockMvc.perform(get("/api/v1/secure/me"))
                .andExpect(status().isUnauthorized())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.status", is(401)))
                .andExpect(jsonPath("$.code", is("auth.unauthenticated")))
                .andExpect(jsonPath("$.type", containsString("auth.unauthenticated")))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void publicPathsAreReachableWithoutAToken() throws Exception {
        mockMvc.perform(get("/api/v1/public/ping"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ok")));
    }

    @Test
    void anAuthenticatedCallerIsReadFromTheTokenNotTheRequest() throws Exception {
        mockMvc.perform(get("/api/v1/secure/me").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.userId", is(USER.toString())));
    }

    // --- authorization --------------------------------------------------------

    @Test
    @DisplayName("a missing permission is 403 problem+json and does not name the permission")
    void missingPermissionIsForbidden() throws Exception {
        mockMvc.perform(get("/api/v1/probe/admin").with(cashier()))
                .andExpect(status().isForbidden())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("auth.forbidden")))
                // Naming the required permission would map out the authorization model.
                .andExpect(jsonPath("$.detail", not(containsString("user:manage"))));
    }

    @Test
    void holdingThePermissionAllowsTheCall() throws Exception {
        mockMvc.perform(
                        get("/api/v1/probe/admin")
                                .with(
                                        jwt().jwt(
                                                        TestApplication.jwtWith(USER)
                                                                .claim(
                                                                        "perms",
                                                                        List.of("user:manage"))
                                                                .build())
                                                .authorities(
                                                        new SimpleGrantedAuthority("user:manage"))))
                .andExpect(status().isOk());
    }

    @Test
    @DisplayName("a cashier cannot act in a branch they are not assigned to")
    void branchGuardBlocksOtherBranches() throws Exception {
        mockMvc.perform(get("/api/v1/probe/branch/" + HOME_BRANCH).with(cashier()))
                .andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/probe/branch/" + OTHER_BRANCH).with(cashier()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("branch.access_denied")))
                // Must not disclose whether that branch exists.
                .andExpect(jsonPath("$.detail", not(containsString(OTHER_BRANCH.toString()))));
    }

    // --- error shape ----------------------------------------------------------

    @Test
    void domainErrorsCarryAStableMachineReadableCode() throws Exception {
        mockMvc.perform(get("/api/v1/probe/not-found").with(cashier()))
                .andExpect(status().isNotFound())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("product.not_found")))
                .andExpect(jsonPath("$.status", is(404)))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }

    @Test
    void businessRuleFailuresReturn422WithTheirContext() throws Exception {
        mockMvc.perform(get("/api/v1/probe/business-rule").with(cashier()))
                .andExpect(status().isUnprocessableEntity())
                .andExpect(jsonPath("$.code", is("sale.outside_returns_window")))
                .andExpect(jsonPath("$.daysSinceSale", is(45)))
                .andExpect(jsonPath("$.windowDays", is(30)));
    }

    @Test
    @DisplayName("validation failures list the fields but never echo the submitted values")
    void validationErrorsDoNotEchoRejectedValues() throws Exception {
        String body = "{\"email\":\"not-an-email\",\"password\":\"hunter2\"}";

        mockMvc.perform(
                        post("/api/v1/probe/validate")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("request.validation_failed")))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field", is("email")))
                // The rejected password must not come back in the error document.
                .andExpect(content().string(not(containsString("hunter2"))));
    }

    @Test
    @DisplayName("an unexpected exception returns a generic 500 and leaks no internals")
    void unexpectedErrorsLeakNothing() throws Exception {
        mockMvc.perform(get("/api/v1/probe/unexpected").with(cashier()))
                .andExpect(status().isInternalServerError())
                .andExpect(jsonPath("$.code", is("internal.error")))
                .andExpect(content().string(not(containsString("postgres"))))
                .andExpect(content().string(not(containsString("10.0.0.5"))))
                .andExpect(content().string(not(containsString("sales_user"))));
    }

    // --- correlation ----------------------------------------------------------

    @Test
    void aSuppliedCorrelationIdIsEchoedBack() throws Exception {
        mockMvc.perform(get("/api/v1/public/ping").header(CorrelationId.HEADER, "trace-abc-123"))
                .andExpect(header().string(CorrelationId.HEADER, is("trace-abc-123")));
    }

    @Test
    void aCorrelationIdIsGeneratedWhenTheClientSendsNone() throws Exception {
        mockMvc.perform(get("/api/v1/public/ping"))
                .andExpect(
                        header().string(CorrelationId.HEADER, matchesPattern("[A-Za-z0-9._-]+")));
    }

    @Test
    @DisplayName("a hostile correlation header is discarded, not written into the logs")
    void aMaliciousCorrelationIdIsReplaced() throws Exception {
        // Newlines would let a caller forge log entries; the value must be rejected outright.
        mockMvc.perform(
                        get("/api/v1/public/ping")
                                .header(CorrelationId.HEADER, "abc\ninjected ERROR fake log line"))
                .andExpect(header().string(CorrelationId.HEADER, not(containsString("injected"))))
                .andExpect(
                        header().string(CorrelationId.HEADER, matchesPattern("[A-Za-z0-9._-]+")));
    }

    @Test
    void anOverlongCorrelationIdIsReplaced() throws Exception {
        mockMvc.perform(get("/api/v1/public/ping").header(CorrelationId.HEADER, "x".repeat(200)))
                .andExpect(header().string(CorrelationId.HEADER, not(is("x".repeat(200)))));
    }

    // --- an unparseable body --------------------------------------------------

    @Test
    @DisplayName("an invalid enum names the field and the values that would have worked")
    void anInvalidEnumIsActionable() throws Exception {
        mockMvc.perform(
                        post("/api/v1/probe/parse")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"FOUND\",\"count\":1,\"lines\":[]}"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("request.malformed")))
                .andExpect(jsonPath("$.detail", containsString("'reason'")))
                .andExpect(jsonPath("$.detail", containsString("DAMAGE, EXPIRY, OTHER")))
                // The type is named too, so the message still says what kind of value was wanted
                // in the cases where Jackson records no property path.
                .andExpect(jsonPath("$.detail", containsString("ProbeReason")))
                .andExpect(jsonPath("$.errors", hasSize(1)))
                .andExpect(jsonPath("$.errors[0].field", is("reason")))
                // The rejected value is never echoed: the next field like this holds a password.
                .andExpect(jsonPath("$.detail", not(containsString("FOUND"))));
    }

    @Test
    @DisplayName("a bad field inside a list is located by index")
    void aNestedFieldIsLocatedByIndex() throws Exception {
        mockMvc.perform(
                        post("/api/v1/probe/parse")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"reason\":\"DAMAGE\",\"count\":1,"
                                                + "\"lines\":[{\"reason\":\"DAMAGE\",\"quantity\":1},"
                                                + "{\"reason\":\"NOPE\",\"quantity\":2}]}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.errors[0].field", is("lines[1].reason")))
                .andExpect(jsonPath("$.detail", containsString("lines[1].reason")));
    }

    @Test
    void aWrongTypeNamesItsFieldWithoutEchoingTheValue() throws Exception {
        mockMvc.perform(
                        post("/api/v1/probe/parse")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"DAMAGE\",\"count\":\"seventeen\"}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("request.malformed")))
                .andExpect(jsonPath("$.errors[0].field", is("count")))
                .andExpect(jsonPath("$.detail", not(containsString("seventeen"))));
    }

    @Test
    @DisplayName("a body that is not JSON at all still gets the standard shape")
    void aBodyThatIsNotJsonIsStillAProblemResponse() throws Exception {
        mockMvc.perform(
                        post("/api/v1/probe/parse")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("not json"))
                .andExpect(status().isBadRequest())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code", is("request.malformed")))
                .andExpect(jsonPath("$.correlationId", notNullValue()));
    }
}
