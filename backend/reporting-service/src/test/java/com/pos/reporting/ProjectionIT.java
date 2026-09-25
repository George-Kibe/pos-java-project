package com.pos.reporting;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.reporting.service.DashboardService;
import com.pos.reporting.service.RebuildService;
import com.pos.reporting.service.ReportFilter;
import com.pos.reporting.service.ReportQueries;
import com.pos.reporting.service.ShiftReportService;
import com.pos.reporting.service.StockReportService;

/**
 * The roadmap's test: a projection rebuilt from nothing reproduces exactly the numbers the
 * incremental one produced, and a Z-report reconciles with the till's own figures to the cent.
 */
class ProjectionIT extends ReportingTestBase {

    @Autowired private ReportQueries reports;
    @Autowired private ShiftReportService shifts;
    @Autowired private StockReportService stock;
    @Autowired private DashboardService dashboards;
    @Autowired private RebuildService rebuild;

    /** Every report this service produces for the fixture day, as one comparable value. */
    private record Everything(
            List<ReportQueries.SalesRow> daily,
            List<ReportQueries.SalesRow> byBranch,
            List<ReportQueries.SalesRow> byCashier,
            List<ReportQueries.ProductRow> byProduct,
            List<ReportQueries.CategoryRow> byCategory,
            List<ReportQueries.TenderRow> paymentMix,
            List<ReportQueries.HourRow> byHour,
            List<ReportQueries.ShrinkageRow> shrinkage,
            java.math.BigDecimal returns,
            ShiftReportService.ShiftReport zReport,
            ShiftReportService.BranchDay branchDay,
            StockReportService.Valuation valuation,
            List<StockReportService.DeadLine> dead,
            List<StockReportService.ExpiringLine> expiring,
            DashboardService.Dashboard dashboard) {}

    private Everything everything() {
        ReportFilter filter = ReportFilter.day(today(), BRANCH);
        return new Everything(
                reports.salesDaily(filter),
                reports.salesByBranch(filter),
                reports.salesByCashier(filter),
                reports.salesByProduct(filter),
                reports.marginByCategory(filter),
                reports.paymentMix(filter),
                reports.salesByHour(filter),
                reports.shrinkage(filter),
                reports.returnsTotal(filter),
                shifts.forShift(shift),
                shifts.forBranchDay(BRANCH, today()),
                stock.latestValuation(BRANCH, null).orElseThrow(),
                stock.deadStock(BRANCH, 30, today()),
                stock.nearExpiry(BRANCH, 7, today()),
                dashboards.forDay(BRANCH, today()));
    }

    @Test
    @DisplayName("the day's numbers, worked out by hand, from events that arrived out of order")
    void theNumbersAreRight() {
        publishAll(aDayOfTrading());

        Everything day = everything();

        // Two sales count: the third was voided.
        ReportQueries.SalesRow sales = day.daily().getFirst();
        assertThat(sales.baskets()).isEqualTo(2);
        assertThat(sales.grossSales()).isEqualByComparingTo("558.00");
        assertThat(sales.netSales()).isEqualByComparingTo("510.00");
        assertThat(sales.tax()).isEqualByComparingTo("48.00");
        assertThat(sales.averageBasket()).isEqualByComparingTo("279.00");
        assertThat(day.returns()).isEqualByComparingTo("116.00");

        // Cash net of change; the voided sale's tender is not takings.
        assertThat(day.paymentMix())
                .extracting(ReportQueries.TenderRow::method, ReportQueries.TenderRow::amount)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("CASH", money("408.0000")),
                        org.assertj.core.groups.Tuple.tuple("MPESA", money("150.0000")));

        // Soap: 3 sold, 1 back (resaleable, so its cost comes back too).
        ReportQueries.ProductRow soap =
                day.byProduct().stream()
                        .filter(row -> row.productId().equals(SOAP))
                        .findFirst()
                        .orElseThrow();
        assertThat(soap.quantitySold()).isEqualByComparingTo("3");
        assertThat(soap.quantityReturned()).isEqualByComparingTo("1");
        assertThat(soap.netSales()).isEqualByComparingTo("200.00");
        assertThat(soap.cost()).isEqualByComparingTo("160.00");
        assertThat(soap.margin()).isEqualByComparingTo("40.00");
        assertThat(soap.categoryCode()).isEqualTo("HOUSEHOLD");
        assertThat(soap.uncostedQuantity()).isEqualByComparingTo("0");

        assertThat(day.byCategory())
                .extracting(
                        ReportQueries.CategoryRow::categoryCode,
                        ReportQueries.CategoryRow::marginPercent)
                .containsExactly(
                        org.assertj.core.groups.Tuple.tuple("FOOD", money("28.57")),
                        org.assertj.core.groups.Tuple.tuple("HOUSEHOLD", money("20.00")));

        // Stock: a complete two-page snapshot, the dusty tin unsold, the soap batch lapsing.
        assertThat(day.valuation().totalValue()).isEqualByComparingTo("2750.00");
        assertThat(day.dead())
                .singleElement()
                .satisfies(line -> assertThat(line.productId()).isEqualTo(DUSTY));
        assertThat(day.expiring())
                .singleElement()
                .satisfies(line -> assertThat(line.valueAtCost()).isEqualByComparingTo("240.00"));

        assertThat(day.dashboard().grossMargin()).isEqualByComparingTo("100.00");
        assertThat(day.dashboard().topMovers().getFirst().productId()).isEqualTo(SOAP);
        assertThat(day.dashboard().deadStockValue()).isEqualByComparingTo("400.00");
        assertThat(day.dashboard().nearExpiryValue()).isEqualByComparingTo("240.00");
        assertThat(day.dashboard().stockValue()).isEqualByComparingTo("2750.00");

        // Shrinkage: only what was lost, largest first; the bar found again is not shrinkage.
        assertThat(day.shrinkage()).hasSize(2);
        assertThat(day.shrinkage().getFirst().reasonCode()).isEqualTo("DAMAGE");
        assertThat(day.shrinkage().getFirst().quantity()).isEqualByComparingTo("2");
        assertThat(day.shrinkage().getFirst().valueAtCost()).isEqualByComparingTo("160.00");
        assertThat(day.shrinkage().get(1).reasonCode()).isEqualTo("STOCK_TAKE");
        assertThat(day.shrinkage().get(1).valueAtCost()).isEqualByComparingTo("90.00");

        // By hour: every basket of the day in some hour, and items per basket from the lines.
        assertThat(day.byHour().stream().mapToLong(ReportQueries.HourRow::baskets).sum())
                .isEqualTo(day.daily().stream().mapToLong(ReportQueries.SalesRow::baskets).sum());
        assertThat(day.byHour()).allSatisfy(row -> assertThat(row.hour()).isBetween(0, 23));
    }

    @Test
    @DisplayName("a Z-report reconciles against the till's own figures to the cent")
    void theZReportReconciles() {
        publishAll(aDayOfTrading());

        ShiftReportService.ShiftReport z = shifts.forShift(shift);

        assertThat(z.kind()).isEqualTo("Z");
        assertThat(z.reconciled()).isTrue();
        assertThat(z.differences()).isEmpty();
        assertThat(z.totals().cashSales()).isEqualByComparingTo("524.00");
        assertThat(z.totals().cashRefunds()).isEqualByComparingTo("232.00");
        assertThat(z.totals().nonCashSales()).isEqualByComparingTo("150.00");
        assertThat(z.expectedCash()).isEqualByComparingTo("1192.00");
        assertThat(z.till().variance()).isEqualByComparingTo("-2.00");
        assertThat(shifts.forBranchDay(BRANCH, today()).reconciled()).isTrue();
    }

    @Test
    @DisplayName("a sale reporting never heard of is caught by the Z-report, figure by figure")
    void aMissingSaleBreaksReconciliation() {
        List<Published> day = aDayOfTrading();
        // Lose sale two on its way here.
        publishAll(
                day.stream()
                        .filter(
                                event ->
                                        !(event.topic().endsWith("sale-completed.v1")
                                                && event.key().equals(saleTwo)))
                        .toList());

        ShiftReportService.ShiftReport z = shifts.forShift(shift);

        assertThat(z.reconciled()).isFalse();
        assertThat(z.differences())
                .extracting(difference -> difference.figure())
                .contains("cashSales", "nonCashSales", "saleCount");
    }

    @Test
    @DisplayName("rebuilt from nothing, the read models reproduce exactly the same numbers")
    void aRebuildReproducesTheIncrementalNumbers() {
        publishAll(aDayOfTrading());
        Everything incremental = everything();

        RebuildService.Result result = rebuild.rebuild();

        assertThat(result.eventsReplayed()).isEqualTo(logged());
        Everything rebuilt = everything();
        // Not "close": equal, every row and every figure.
        assertThat(rebuilt)
                .usingRecursiveComparison()
                .ignoringFields("dashboard.businessDate")
                .isEqualTo(incremental);
    }

    @Test
    @DisplayName("a redelivered event is logged and projected once")
    void aRedeliveryChangesNothing() {
        List<Published> day = aDayOfTrading();
        publishAll(day);
        Everything before = everything();
        long logged = logged();

        // The whole day again, as a consumer restart would redeliver it.
        day.forEach(this::publish);
        // Wait on a fresh event that will be taken in after the redeliveries on its partition.
        List<Published> marker = aDayOfTrading().subList(9, 10);
        publish(marker.getFirst());
        eventually(Duration.ofSeconds(30), "the marker", () -> logged() == logged + 1);

        assertThat(everything()).usingRecursiveComparison().isEqualTo(before);
    }

    @Test
    @DisplayName("stock with no known cost is reported as uncosted, never as costing nothing")
    void uncostedStockIsNotFreeStock() {
        List<Published> day = new java.util.ArrayList<>(aDayOfTrading());
        // Soap: one of the three sold ahead of its delivery, so only two came out of a batch.
        day.replaceAll(
                event ->
                        event.topic().equals(com.pos.events.Topics.INVENTORY_STOCK_DEDUCTED)
                                        && event.key().equals(saleOne)
                                ? event(
                                        event.topic(),
                                        saleOne,
                                        deducted(saleOne, SOAP, "3", "2", "80.00"))
                                : event);
        // Flour: inventory has not reported the deduction at all yet.
        day.removeIf(
                event ->
                        event.topic().equals(com.pos.events.Topics.INVENTORY_STOCK_DEDUCTED)
                                && event.key().equals(saleTwo));
        publishAll(day);

        ReportFilter filter = ReportFilter.day(today(), BRANCH);
        List<ReportQueries.ProductRow> products = reports.salesByProduct(filter);

        ReportQueries.ProductRow soap =
                products.stream().filter(row -> row.productId().equals(SOAP)).findFirst().get();
        assertThat(soap.uncostedQuantity()).isEqualByComparingTo("1");
        // Two costed at 80, one of them back on the shelf at its own cost.
        assertThat(soap.cost()).isEqualByComparingTo("80.00");
        ReportQueries.ProductRow flour =
                products.stream().filter(row -> row.productId().equals(FLOUR)).findFirst().get();
        assertThat(flour.uncostedQuantity()).isEqualByComparingTo("1");
        assertThat(flour.cost()).isEqualByComparingTo("0");
        assertThat(reports.marginByCategory(filter))
                .extracting(ReportQueries.CategoryRow::uncostedQuantity)
                .usingElementComparator(java.math.BigDecimal::compareTo)
                .containsExactly(money("1"), money("1"));
    }

    @Test
    @DisplayName("half a stock snapshot is not a stock value")
    void anIncompleteSnapshotIsIgnored() {
        List<Published> day = aDayOfTrading();
        // Only the second page of the snapshot arrives.
        publishAll(
                day.stream()
                        .filter(
                                event ->
                                        !(event.topic().endsWith("stock-valued.v1")
                                                && day.indexOf(event) == 12))
                        .toList());

        assertThat(stock.latestValuation(BRANCH, null)).isEmpty();
    }
}
