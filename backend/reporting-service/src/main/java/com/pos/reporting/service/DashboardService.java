package com.pos.reporting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.LocalDate;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * The numbers a manager looks at first, for one branch and one day.
 *
 * <p>Built from the same queries as the full reports, never from its own sums, so the dashboard and
 * the report it drills into cannot disagree.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class DashboardService {

    private static final int TOP_MOVERS = 10;
    private static final int DEAD_AFTER_DAYS = 30;
    private static final int EXPIRY_WINDOW_DAYS = 7;

    private final ReportQueries reports;
    private final StockReportService stock;

    public record Mover(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            BigDecimal netSales) {}

    public record Dashboard(
            UUID branchId,
            LocalDate businessDate,
            BigDecimal grossSales,
            BigDecimal netSales,
            long baskets,
            BigDecimal averageBasket,
            BigDecimal refunds,
            BigDecimal grossMargin,
            BigDecimal grossMarginPercent,
            List<Mover> topMovers,
            long deadStockLines,
            BigDecimal deadStockValue,
            BigDecimal nearExpiryValue,
            BigDecimal stockValue) {}

    public Dashboard forDay(UUID branchId, LocalDate day) {
        ReportFilter filter = ReportFilter.day(day, branchId);

        List<ReportQueries.SalesRow> sales = reports.salesByBranch(filter);
        long baskets = sales.stream().mapToLong(ReportQueries.SalesRow::baskets).sum();
        BigDecimal gross = sum(sales.stream().map(ReportQueries.SalesRow::grossSales).toList());
        BigDecimal net = sum(sales.stream().map(ReportQueries.SalesRow::netSales).toList());

        List<ReportQueries.ProductRow> products = reports.salesByProduct(filter);
        BigDecimal productNet =
                sum(products.stream().map(ReportQueries.ProductRow::netSales).toList());
        BigDecimal margin = sum(products.stream().map(ReportQueries.ProductRow::margin).toList());

        List<StockReportService.DeadLine> dead = stock.deadStock(branchId, DEAD_AFTER_DAYS, day);

        return new Dashboard(
                branchId,
                day,
                gross,
                net,
                baskets,
                baskets == 0
                        ? ReportQueries.money(BigDecimal.ZERO)
                        : gross.divide(BigDecimal.valueOf(baskets), 4, RoundingMode.HALF_UP),
                ReportQueries.money(reports.returnsTotal(filter)),
                margin,
                ReportQueries.percent(margin, productNet),
                products.stream()
                        .filter(row -> row.quantitySold().signum() > 0)
                        .sorted(
                                Comparator.comparing(ReportQueries.ProductRow::quantitySold)
                                        .reversed()
                                        .thenComparing(
                                                ReportQueries.ProductRow::sku,
                                                Comparator.nullsLast(Comparator.naturalOrder())))
                        .limit(TOP_MOVERS)
                        .map(
                                row ->
                                        new Mover(
                                                row.productId(),
                                                row.sku(),
                                                row.productName(),
                                                row.quantitySold(),
                                                row.netSales()))
                        .toList(),
                dead.size(),
                sum(dead.stream().map(StockReportService.DeadLine::valueAtCost).toList()),
                sum(
                        stock.nearExpiry(branchId, EXPIRY_WINDOW_DAYS, day).stream()
                                .map(StockReportService.ExpiringLine::valueAtCost)
                                .toList()),
                stock.latestValuation(branchId, null)
                        .map(StockReportService.Valuation::totalValue)
                        .orElse(ReportQueries.money(BigDecimal.ZERO)));
    }

    private static BigDecimal sum(List<BigDecimal> values) {
        return ReportQueries.money(values.stream().reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
