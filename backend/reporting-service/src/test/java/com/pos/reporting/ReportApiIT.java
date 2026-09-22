package com.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.containsString;
import static org.hamcrest.Matchers.greaterThanOrEqualTo;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pos.reporting.service.ReportTables;

/** Who may see what, and every report as JSON, CSV and PDF. */
class ReportApiIT extends ReportingTestBase {

    @BeforeEach
    void aDayHasBeenTraded() {
        publishAll(aDayOfTrading());
    }

    private MockHttpServletRequestBuilder range(String path) {
        return get(path).param("from", today().toString()).param("to", today().toString());
    }

    // --- the reports --------------------------------------------------------------------

    @Test
    void theSalesReportsAnswer() throws Exception {
        mockMvc.perform(
                        range("/api/v1/reports/sales/daily")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].baskets", is(2)))
                .andExpect(jsonPath("$[0].grossSales", is(558.0)));
        mockMvc.perform(range("/api/v1/reports/sales/by-branch").with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(
                        range("/api/v1/reports/sales/by-cashier")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$[0].cashierId", is(CASHIER.toString())));
        mockMvc.perform(
                        range("/api/v1/reports/sales/by-product")
                                .param("branchId", BRANCH.toString())
                                .param("categoryId", HOUSEHOLD.toString())
                                .with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(1)))
                .andExpect(jsonPath("$[0].sku", is("SOAP")));
        mockMvc.perform(
                        range("/api/v1/reports/margin/by-category")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$", hasSize(2)));
        mockMvc.perform(
                        range("/api/v1/reports/payment-mix")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$[0].method", is("CASH")));
    }

    @Test
    void theShiftAndStockReportsAnswer() throws Exception {
        mockMvc.perform(get("/api/v1/reports/shifts/" + shift).with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind", is("Z")))
                .andExpect(jsonPath("$.reconciled", is(true)));
        mockMvc.perform(
                        get("/api/v1/reports/branches/" + BRANCH + "/days/" + today() + "/z-report")
                                .with(headOffice()))
                .andExpect(jsonPath("$.shifts", hasSize(1)))
                .andExpect(jsonPath("$.reconciled", is(true)));
        mockMvc.perform(
                        get("/api/v1/reports/stock/valuation")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$.totalValue", is(2750.0)));
        mockMvc.perform(
                        get("/api/v1/reports/stock/valuation/trend")
                                .param("branchId", BRANCH.toString())
                                .param("from", today().minusDays(1).toString())
                                .param("to", today().toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(
                        get("/api/v1/reports/stock/dead")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$[0].sku", is("DUSTY")));
        mockMvc.perform(
                        get("/api/v1/reports/stock/near-expiry")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$", hasSize(1)));
        mockMvc.perform(
                        get("/api/v1/dashboards")
                                .param("branchId", BRANCH.toString())
                                .with(branchManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.baskets", is(2)))
                .andExpect(jsonPath("$.stockValue", is(2750.0)));
    }

    @Test
    @DisplayName("a shift still open is an X-report, and says the float and drops come at close")
    void anOpenShiftIsAnXReport() throws Exception {
        // Only sales: no close yet.
        jdbc.sql("DELETE FROM report_shifts").update();

        mockMvc.perform(get("/api/v1/reports/shifts/" + shift).with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.kind", is("X")))
                .andExpect(jsonPath("$.expectedCash", is(292.0)));
        mockMvc.perform(
                        get("/api/v1/reports/shifts/" + java.util.UUID.randomUUID())
                                .with(headOffice()))
                .andExpect(status().isNotFound());
    }

    // --- exports --------------------------------------------------------------------------

    @Test
    @DisplayName("every report exports as CSV and PDF with the screen's figures")
    void everyReportExports() throws Exception {
        for (String report : ReportTables.REPORTS) {
            for (String format : new String[] {"csv", "pdf"}) {
                byte[] body =
                        mockMvc.perform(
                                        range("/api/v1/reports/exports/" + report)
                                                .param("format", format)
                                                .param("branchId", BRANCH.toString())
                                                .with(headOffice()))
                                .andExpect(status().isOk())
                                .andExpect(
                                        header().string(
                                                        "Content-Disposition",
                                                        containsString(report)))
                                .andReturn()
                                .getResponse()
                                .getContentAsByteArray();
                if (format.equals("pdf")) {
                    assertThat(new String(body, 0, 5, StandardCharsets.US_ASCII))
                            .isEqualTo("%PDF-");
                } else {
                    assertThat(body.length).isGreaterThan(3);
                }
            }
        }
        String csv =
                mockMvc.perform(
                                range("/api/v1/reports/exports/sales-by-product")
                                        .param("branchId", BRANCH.toString())
                                        .with(headOffice()))
                        .andReturn()
                        .getResponse()
                        .getContentAsString(StandardCharsets.UTF_8);
        assertThat(csv).contains("SOAP,SOAP,HOUSEHOLD,3.000,1.000,200.00,160.00,40.00,20.00,0.000");
    }

    @Test
    void aZReportExportsAsAPdf() throws Exception {
        mockMvc.perform(get("/api/v1/reports/shifts/" + shift + "/export").with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(header().string("Content-Type", "application/pdf"));
        mockMvc.perform(
                        get("/api/v1/reports/shifts/" + shift + "/export")
                                .param("format", "csv")
                                .with(headOffice()))
                .andExpect(status().isOk());
    }

    @Test
    void exportsAreRefusedWithoutTheExportPermissionOrTheRightFormat() throws Exception {
        mockMvc.perform(
                        range("/api/v1/reports/exports/sales-daily")
                                .param("branchId", BRANCH.toString())
                                .with(branchManager()))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        range("/api/v1/reports/exports/sales-daily")
                                .param("format", "xlsx")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(status().isBadRequest());
        mockMvc.perform(range("/api/v1/reports/exports/no-such-report").with(headOffice()))
                .andExpect(status().isNotFound());
        mockMvc.perform(range("/api/v1/reports/exports/stock-valuation").with(headOffice()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("report.branch_required")));
    }

    // --- access -------------------------------------------------------------------------

    @Test
    @DisplayName("a branch manager sees their own branch, must name it, and cannot see another")
    void branchReportsAreBranchScoped() throws Exception {
        mockMvc.perform(
                        range("/api/v1/reports/sales/daily")
                                .param("branchId", BRANCH.toString())
                                .with(branchManager()))
                .andExpect(status().isOk());
        mockMvc.perform(range("/api/v1/reports/sales/daily").with(branchManager()))
                .andExpect(status().isForbidden())
                .andExpect(jsonPath("$.code", is("report.branch_required")));
        mockMvc.perform(
                        range("/api/v1/reports/sales/daily")
                                .param("branchId", OTHER_BRANCH.toString())
                                .with(branchManager()))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/reports/shifts/" + shift)
                                .with(at(OTHER_BRANCH, "report:view:branch")))
                .andExpect(status().isForbidden());
    }

    @Test
    void reportsAreNotPublic() throws Exception {
        mockMvc.perform(range("/api/v1/reports/sales/daily")).andExpect(status().isUnauthorized());
        mockMvc.perform(get("/api/v1/dashboards").param("branchId", BRANCH.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    void aBackwardsOrEndlessRangeIsRefused() throws Exception {
        mockMvc.perform(
                        get("/api/v1/reports/sales/daily")
                                .param("from", today().toString())
                                .param("to", today().minusDays(1).toString())
                                .with(headOffice()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("report.range_backwards")));
        mockMvc.perform(
                        get("/api/v1/reports/stock/dead")
                                .param("branchId", BRANCH.toString())
                                .param("days", "0")
                                .with(headOffice()))
                .andExpect(status().isBadRequest());
    }

    // --- operations -------------------------------------------------------------------------

    @Test
    @DisplayName("a rebuild needs head-office rights and reports how much it replayed")
    void theRebuildEndpoint() throws Exception {
        mockMvc.perform(post("/api/v1/reports/rebuild").with(branchManager()))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/reports/rebuild").with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.eventsReplayed", is((int) logged())));
    }

    @Test
    @DisplayName("lag is measured against the broker, and says how fresh the reports are")
    void lagIsReported() throws Exception {
        mockMvc.perform(get("/api/v1/reports/lag").with(branchManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.consumerGroup", is("reporting-test")))
                .andExpect(jsonPath("$.totalLag", greaterThanOrEqualTo(0)))
                .andExpect(jsonPath("$.lastEventAt", org.hamcrest.Matchers.notNullValue()));
    }
}
