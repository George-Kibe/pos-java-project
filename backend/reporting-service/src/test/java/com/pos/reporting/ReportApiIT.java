package com.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.contains;
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
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;

import com.pos.events.Topics;
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

    private static Published expense(
            UUID id, UUID branch, String category, String amount, String status, long revision) {
        return event(
                Topics.PURCHASING_EXPENSE_CHANGED,
                id,
                new com.pos.events.purchasing.ExpenseChangedPayload(
                        id,
                        "EXP-" + revision,
                        branch,
                        category,
                        category + " for the day",
                        today(),
                        money(amount),
                        money("0"),
                        "KES",
                        status,
                        revision));
    }

    @Test
    @DisplayName(
            "profit and loss: net sales less cost, losses and approved expenses, head office's"
                    + " once")
    void profitAndLoss() throws Exception {
        UUID voided = UUID.randomUUID();
        publishAll(
                List.of(
                        expense(UUID.randomUUID(), BRANCH, "ELECTRICITY", "50", "APPROVED", 0),
                        // Waiting for approval: not counted.
                        expense(UUID.randomUUID(), BRANCH, "RENT", "1000", "PENDING_APPROVAL", 0),
                        // Head office: in the business's figure, not the branch's.
                        expense(UUID.randomUUID(), null, "WAGES", "20", "APPROVED", 0),
                        // Its void arrives before its recording, and still wins.
                        expense(voided, BRANCH, "TRANSPORT", "7", "VOIDED", 2),
                        expense(voided, BRANCH, "TRANSPORT", "7", "APPROVED", 1)));

        // Net sales 300 - 100 returned + 210 = 410; cost 240 - 80 + 150 = 310; losses 160 + 90.
        mockMvc.perform(
                        range("/api/v1/reports/profit-and-loss")
                                .param("branchId", BRANCH.toString())
                                .with(branchManager()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.total.netSales", is(410.0)))
                .andExpect(jsonPath("$.total.costOfSales", is(310.0)))
                .andExpect(jsonPath("$.total.grossProfit", is(100.0)))
                .andExpect(jsonPath("$.total.lossTotal", is(250.0)))
                .andExpect(jsonPath("$.total.profitAfterLosses", is(-150.0)))
                .andExpect(jsonPath("$.total.expenseTotal", is(50.0)))
                .andExpect(jsonPath("$.total.netProfit", is(-200.0)))
                .andExpect(jsonPath("$.headOfficeExpenses", hasSize(0)));
        mockMvc.perform(range("/api/v1/reports/profit-and-loss").with(branchManager()))
                .andExpect(status().isForbidden());
        mockMvc.perform(range("/api/v1/reports/profit-and-loss").with(headOffice()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.branches", hasSize(1)))
                .andExpect(jsonPath("$.headOfficeTotal", is(20.0)))
                .andExpect(jsonPath("$.total.expenseTotal", is(70.0)))
                .andExpect(jsonPath("$.total.netProfit", is(-220.0)));

        // By product: soap earned 100 - 80... and lost 160 of it.
        mockMvc.perform(
                        range("/api/v1/reports/profit-and-loss/products")
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(
                        jsonPath("$[?(@.productId == '" + SOAP + "')].grossProfit", contains(40.0)))
                .andExpect(jsonPath("$[?(@.productId == '" + SOAP + "')].losses", contains(160.0)))
                .andExpect(
                        jsonPath(
                                "$[?(@.productId == '" + SOAP + "')].profitAfterLosses",
                                contains(-120.0)))
                .andExpect(
                        jsonPath(
                                "$[?(@.productId == '" + FLOUR + "')].profitAfterLosses",
                                contains(-30.0)));

        // A rebuild from the event log comes to the same figures.
        mockMvc.perform(post("/api/v1/reports/rebuild").with(headOffice()))
                .andExpect(status().isOk());
        mockMvc.perform(range("/api/v1/reports/profit-and-loss").with(headOffice()))
                .andExpect(jsonPath("$.total.netProfit", is(-220.0)));
    }

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
    @DisplayName("sales roll up by week, month, quarter and year, per branch or across all of them")
    void salesRollUpByPeriod() throws Exception {
        java.time.LocalDate day = today();
        java.time.LocalDate monday = day.with(java.time.DayOfWeek.MONDAY);
        for (String period : java.util.List.of("WEEK", "MONTH", "QUARTER", "YEAR")) {
            mockMvc.perform(
                            get("/api/v1/reports/sales/by-period")
                                    .param("period", period)
                                    .param("from", day.withDayOfYear(1).toString())
                                    .param("to", day.toString())
                                    .param("acrossBranches", "true")
                                    .with(headOffice()))
                    .andExpect(status().isOk())
                    .andExpect(jsonPath("$", hasSize(1)))
                    .andExpect(jsonPath("$[0].baskets", is(2)))
                    .andExpect(jsonPath("$[0].grossSales", is(558.0)))
                    // Rolled together: no branch on the row.
                    .andExpect(jsonPath("$[0].branchId").doesNotExist());
        }
        mockMvc.perform(
                        get("/api/v1/reports/sales/by-period")
                                .param("period", "WEEK")
                                .param("from", day.toString())
                                .param("to", day.toString())
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$[0].businessDate", is(monday.toString())))
                .andExpect(jsonPath("$[0].branchId", is(BRANCH.toString())));
        mockMvc.perform(
                        get("/api/v1/reports/sales/by-period")
                                .param("period", "MONTH")
                                .param("from", day.toString())
                                .param("to", day.toString())
                                .param("branchId", BRANCH.toString())
                                .with(headOffice()))
                .andExpect(jsonPath("$[0].businessDate", is(day.withDayOfMonth(1).toString())));

        // A branch manager sees their own branch by period, never the whole business.
        mockMvc.perform(
                        get("/api/v1/reports/sales/by-period")
                                .param("period", "YEAR")
                                .param("from", day.toString())
                                .param("to", day.toString())
                                .param("acrossBranches", "true")
                                .with(branchManager()))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        get("/api/v1/reports/sales/by-period")
                                .param("period", "FORTNIGHT")
                                .param("from", day.toString())
                                .param("to", day.toString())
                                .with(headOffice()))
                .andExpect(status().isBadRequest());
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
