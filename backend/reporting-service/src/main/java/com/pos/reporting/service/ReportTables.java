package com.pos.reporting.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.reporting.domain.policy.ShiftArithmetic;
import com.pos.reporting.service.ExportService.Qty;
import com.pos.reporting.service.ExportService.Table;

import lombok.RequiredArgsConstructor;

/**
 * Every exportable report, laid out as a table.
 *
 * <p>Built from the same queries the screens use, so an export says exactly what the screen said.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportTables {

    /** The reports that can be exported, by the name used in the URL. */
    public static final List<String> REPORTS =
            List.of(
                    "sales-daily",
                    "sales-by-branch",
                    "sales-by-cashier",
                    "sales-by-product",
                    "margin-by-category",
                    "payment-mix",
                    "stock-valuation",
                    "dead-stock",
                    "near-expiry");

    private final ReportQueries reports;
    private final StockReportService stock;
    private final ShiftReportService shifts;

    public Table build(String report, ReportFilter filter, int days) {
        List<String> subtitle = subtitle(filter);
        return switch (report) {
            case "sales-daily" -> sales("Sales by day", subtitle, reports.salesDaily(filter));
            case "sales-by-branch" ->
                    sales("Sales by branch", subtitle, reports.salesByBranch(filter));
            case "sales-by-cashier" ->
                    sales("Sales by cashier", subtitle, reports.salesByCashier(filter));
            case "sales-by-product" -> products(subtitle, reports.salesByProduct(filter));
            case "margin-by-category" -> categories(subtitle, reports.marginByCategory(filter));
            case "payment-mix" -> paymentMix(subtitle, reports.paymentMix(filter));
            case "stock-valuation" -> valuation(requireBranch(filter), filter.categoryId());
            case "dead-stock" -> deadStock(requireBranch(filter), days, filter.to());
            case "near-expiry" -> nearExpiry(requireBranch(filter), days, filter.to());
            default ->
                    throw new Errors.NotFoundException(
                            "report.unknown", "No report called " + report);
        };
    }

    public Table shift(UUID shiftId) {
        ShiftReportService.ShiftReport shift = shifts.forShift(shiftId);
        ShiftArithmetic.Totals totals = shift.totals();
        List<List<Object>> rows = new ArrayList<>();
        rows.add(List.of("Sales", Long.valueOf(totals.saleCount()), ""));
        rows.add(List.of("Voids", Long.valueOf(totals.voidCount()), ""));
        rows.add(List.of("Gross sales", "", totals.grossSales()));
        rows.add(List.of("Cash sales", "", totals.cashSales()));
        rows.add(List.of("Non-cash sales", "", totals.nonCashSales()));
        rows.add(List.of("Cash refunds", "", totals.cashRefunds()));
        rows.add(List.of("Non-cash refunds", "", totals.nonCashRefunds()));
        for (Map.Entry<String, java.math.BigDecimal> method : totals.takingsByMethod().entrySet()) {
            rows.add(List.of("Taken by " + method.getKey(), "", method.getValue()));
        }
        if (shift.till() != null) {
            rows.add(List.of("Opening float", "", shift.till().openingFloat()));
            rows.add(List.of("Cash drops", "", shift.till().cashDrops()));
            rows.add(List.of("Expected cash", "", shift.expectedCash()));
            rows.add(List.of("Counted cash", "", shift.till().countedCash()));
            rows.add(List.of("Variance", "", shift.till().variance()));
        } else {
            rows.add(List.of("Cash in less out so far", "", shift.expectedCash()));
        }
        for (ShiftArithmetic.Difference difference : shift.differences()) {
            rows.add(
                    List.of(
                            "DISAGREES: " + difference.figure(),
                            "till " + ExportService.cell(difference.till()),
                            difference.ours()));
        }
        List<String> subtitle = new ArrayList<>();
        subtitle.add("Shift " + shiftId + ", branch " + shift.branchId());
        if (shift.closedAt() != null) {
            subtitle.add("Closed " + shift.closedAt());
        }
        subtitle.add(
                shift.kind().equals("Z")
                        ? (shift.reconciled()
                                ? "Reconciles with the till to the cent"
                                : "DOES NOT reconcile with the till - see the rows marked DISAGREES")
                        : "Shift still open: drops and float appear at close");
        return new Table(
                shift.kind() + "-report",
                subtitle,
                List.of("Figure", "Count", "Amount (" + shift.currency() + ")"),
                rows);
    }

    // --- tables -------------------------------------------------------------------------

    private static Table sales(
            String title, List<String> subtitle, List<ReportQueries.SalesRow> rows) {
        return new Table(
                title,
                subtitle,
                List.of(
                        "Date",
                        "Branch",
                        "Cashier",
                        "Baskets",
                        "Gross",
                        "Net",
                        "Tax",
                        "Average basket"),
                rows.stream()
                        .map(
                                row ->
                                        (List<Object>)
                                                listOf(
                                                        row.businessDate(),
                                                        row.branchId(),
                                                        row.cashierId(),
                                                        row.baskets(),
                                                        row.grossSales(),
                                                        row.netSales(),
                                                        row.tax(),
                                                        row.averageBasket()))
                        .toList());
    }

    private static Table products(List<String> subtitle, List<ReportQueries.ProductRow> rows) {
        return new Table(
                "Sales by product",
                subtitle,
                List.of(
                        "SKU",
                        "Product",
                        "Category",
                        "Sold",
                        "Returned",
                        "Net sales",
                        "Cost",
                        "Margin",
                        "Margin %",
                        "Uncosted qty"),
                rows.stream()
                        .map(
                                row ->
                                        (List<Object>)
                                                listOf(
                                                        row.sku(),
                                                        row.productName(),
                                                        row.categoryCode(),
                                                        new Qty(row.quantitySold()),
                                                        new Qty(row.quantityReturned()),
                                                        row.netSales(),
                                                        row.cost(),
                                                        row.margin(),
                                                        row.marginPercent(),
                                                        new Qty(row.uncostedQuantity())))
                        .toList());
    }

    private static Table categories(List<String> subtitle, List<ReportQueries.CategoryRow> rows) {
        return new Table(
                "Margin by category",
                subtitle,
                List.of("Category", "Net sales", "Cost", "Margin", "Margin %", "Uncosted qty"),
                rows.stream()
                        .map(
                                row ->
                                        (List<Object>)
                                                listOf(
                                                        row.categoryCode(),
                                                        row.netSales(),
                                                        row.cost(),
                                                        row.margin(),
                                                        row.marginPercent(),
                                                        new Qty(row.uncostedQuantity())))
                        .toList());
    }

    private static Table paymentMix(List<String> subtitle, List<ReportQueries.TenderRow> rows) {
        return new Table(
                "Payment mix",
                subtitle,
                List.of("Method", "Tenders", "Amount", "Share %"),
                rows.stream()
                        .map(
                                row ->
                                        (List<Object>)
                                                listOf(
                                                        row.method(),
                                                        row.tenders(),
                                                        row.amount(),
                                                        row.share()))
                        .toList());
    }

    private Table valuation(UUID branchId, UUID categoryId) {
        StockReportService.Valuation valuation =
                stock.latestValuation(branchId, categoryId)
                        .orElseThrow(
                                () ->
                                        new Errors.NotFoundException(
                                                "report.no_valuation",
                                                "Inventory has not valued this branch's stock yet"));
        List<List<Object>> rows =
                new ArrayList<>(
                        valuation.lines().stream()
                                .map(
                                        line ->
                                                (List<Object>)
                                                        listOf(
                                                                line.sku(),
                                                                line.productName(),
                                                                line.categoryCode(),
                                                                new Qty(line.quantityOnHand()),
                                                                line.valueAtCost()))
                                .toList());
        rows.add(listOf("TOTAL", "", "", null, valuation.totalValue()));
        return new Table(
                "Stock valuation",
                List.of("Branch " + branchId, "Valued " + valuation.valuedAt()),
                List.of("SKU", "Product", "Category", "On hand", "Value at cost"),
                rows);
    }

    private Table deadStock(UUID branchId, int days, LocalDate asOf) {
        return new Table(
                "Dead stock",
                List.of("Branch " + branchId, "Unsold for " + days + " days as of " + asOf),
                List.of("SKU", "Product", "On hand", "Value at cost", "Last sold"),
                stock.deadStock(branchId, days, asOf).stream()
                        .map(
                                line ->
                                        (List<Object>)
                                                listOf(
                                                        line.sku(),
                                                        line.productName(),
                                                        new Qty(line.quantityOnHand()),
                                                        line.valueAtCost(),
                                                        line.lastSold()))
                        .toList());
    }

    private Table nearExpiry(UUID branchId, int days, LocalDate asOf) {
        return new Table(
                "Near expiry",
                List.of("Branch " + branchId, "Lapsing within " + days + " days of " + asOf),
                List.of("Batch", "SKU", "Product", "Expires", "Remaining", "Value at cost"),
                stock.nearExpiry(branchId, days, asOf).stream()
                        .map(
                                line ->
                                        (List<Object>)
                                                listOf(
                                                        line.batchNumber(),
                                                        line.sku(),
                                                        line.productName(),
                                                        line.expiryDate(),
                                                        new Qty(line.quantityRemaining()),
                                                        line.valueAtCost()))
                        .toList());
    }

    // --- helpers ------------------------------------------------------------------------

    private static List<String> subtitle(ReportFilter filter) {
        List<String> lines = new ArrayList<>();
        lines.add("From " + filter.from() + " to " + filter.to() + " (Nairobi business days)");
        lines.add(filter.branchId() == null ? "All branches" : "Branch " + filter.branchId());
        if (filter.categoryId() != null) {
            lines.add("Category " + filter.categoryId());
        }
        return lines;
    }

    private static UUID requireBranch(ReportFilter filter) {
        if (filter.branchId() == null) {
            throw new Errors.BadRequestException(
                    "report.branch_required", "Stock reports are per branch: name one");
        }
        return filter.branchId();
    }

    /** List.of rejects nulls, and an empty cell is a legitimate value in a report. */
    private static List<Object> listOf(Object... cells) {
        return java.util.Arrays.asList(cells);
    }
}
