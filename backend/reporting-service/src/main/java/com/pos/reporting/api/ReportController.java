package com.pos.reporting.api;

import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.ContentDisposition;
import org.springframework.http.HttpHeaders;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.error.Errors;
import com.pos.reporting.service.ExportService;
import com.pos.reporting.service.LagMonitor;
import com.pos.reporting.service.RebuildService;
import com.pos.reporting.service.ReportFilter;
import com.pos.reporting.service.ReportQueries;
import com.pos.reporting.service.ReportTables;
import com.pos.reporting.service.ShiftReportService;
import com.pos.reporting.service.StockReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The reports. Every figure here is eventually consistent - built from events that may still be
 * arriving - and {@code /lag} says how far behind they are.
 */
@RestController
@RequestMapping("/api/v1/reports")
@RequiredArgsConstructor
@Tag(name = "Reports")
public class ReportController {

    private static final String VIEW = "hasAnyAuthority('report:view', 'report:view:branch')";

    private final ReportQueries reports;
    private final ShiftReportService shifts;
    private final StockReportService stock;
    private final ReportTables tables;
    private final ExportService exports;
    private final RebuildService rebuild;
    private final LagMonitor lag;
    private final ReportAccess access;

    // --- sales ------------------------------------------------------------------------

    @GetMapping("/sales/daily")
    @PreAuthorize(VIEW)
    @Operation(summary = "Baskets, gross, net and tax per business day and branch; voids excluded")
    public List<ReportQueries.SalesRow> salesDaily(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId) {
        return reports.salesDaily(filter(from, to, branchId, null));
    }

    @GetMapping("/sales/by-period")
    @PreAuthorize(VIEW)
    @Operation(
            summary =
                    "Sales per week, month, quarter or year - per branch, or across every branch"
                            + " with acrossBranches=true (report:view)")
    public List<ReportQueries.SalesRow> salesByPeriod(
            @RequestParam ReportQueries.Period period,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "false") boolean acrossBranches) {
        return reports.salesByPeriod(filter(from, to, branchId, null), period, acrossBranches);
    }

    @GetMapping("/sales/by-branch")
    @PreAuthorize(VIEW)
    @Operation(summary = "Sales per branch over the range")
    public List<ReportQueries.SalesRow> salesByBranch(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId) {
        return reports.salesByBranch(filter(from, to, branchId, null));
    }

    @GetMapping("/sales/by-cashier")
    @PreAuthorize(VIEW)
    @Operation(summary = "Sales per cashier over the range")
    public List<ReportQueries.SalesRow> salesByCashier(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId) {
        return reports.salesByCashier(filter(from, to, branchId, null));
    }

    @GetMapping("/sales/by-product")
    @PreAuthorize(VIEW)
    @Operation(summary = "Per product: sold, returned, net sales, cost at landed cost, margin")
    public List<ReportQueries.ProductRow> salesByProduct(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID categoryId) {
        return reports.salesByProduct(filter(from, to, branchId, categoryId));
    }

    @GetMapping("/margin/by-category")
    @PreAuthorize(VIEW)
    @Operation(summary = "Net sales, cost and margin rolled up to category")
    public List<ReportQueries.CategoryRow> marginByCategory(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID categoryId) {
        return reports.marginByCategory(filter(from, to, branchId, categoryId));
    }

    @GetMapping("/sales/by-hour")
    @PreAuthorize(VIEW)
    @Operation(
            summary =
                    "Sales by hour of the shop's day: baskets, average basket and items per basket")
    public List<ReportQueries.HourRow> salesByHour(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId) {
        return reports.salesByHour(filter(from, to, branchId, null));
    }

    @GetMapping("/stock/shrinkage")
    @PreAuthorize(VIEW)
    @Operation(
            summary =
                    "Stock lost - written off or found short by a count - by reason and product,"
                            + " valued at cost")
    public List<ReportQueries.ShrinkageRow> shrinkage(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID categoryId) {
        return reports.shrinkage(filter(from, to, branchId, categoryId));
    }

    @GetMapping("/payment-mix")
    @PreAuthorize(VIEW)
    @Operation(summary = "Takings by payment method, cash net of change")
    public List<ReportQueries.TenderRow> paymentMix(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId) {
        return reports.paymentMix(filter(from, to, branchId, null));
    }

    // --- shifts -------------------------------------------------------------------------

    @GetMapping("/shifts/{shiftId}")
    @PreAuthorize(VIEW)
    @Operation(
            summary =
                    "Z-report for a closed shift, reconciled against the till to the cent; X-report"
                            + " for one still open")
    public ShiftReportService.ShiftReport shift(@PathVariable UUID shiftId) {
        ShiftReportService.ShiftReport report = shifts.forShift(shiftId);
        access.requireFor(report.branchId());
        return report;
    }

    @GetMapping("/branches/{branchId}/days/{day}/z-report")
    @PreAuthorize(VIEW)
    @Operation(summary = "Every shift a branch closed on a business day, and whether all reconcile")
    public ShiftReportService.BranchDay branchDay(
            @PathVariable UUID branchId,
            @PathVariable @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate day) {
        access.requireFor(branchId);
        return shifts.forBranchDay(branchId, day);
    }

    // --- stock --------------------------------------------------------------------------

    @GetMapping("/stock/valuation")
    @PreAuthorize(VIEW)
    @Operation(summary = "The latest complete stock valuation inventory sent for a branch")
    public StockReportService.Valuation valuation(
            @RequestParam UUID branchId, @RequestParam(required = false) UUID categoryId) {
        access.requireFor(branchId);
        return stock.latestValuation(branchId, categoryId)
                .orElseThrow(
                        () ->
                                new Errors.NotFoundException(
                                        "report.no_valuation",
                                        "Inventory has not valued this branch's stock yet"));
    }

    @GetMapping("/stock/valuation/trend")
    @PreAuthorize(VIEW)
    @Operation(summary = "Stock value by day: each day's last complete snapshot")
    public List<StockReportService.TrendPoint> valuationTrend(
            @RequestParam UUID branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to) {
        access.requireFor(branchId);
        filter(from, to, branchId, null);
        return stock.valuationTrend(branchId, from, to);
    }

    @GetMapping("/stock/dead")
    @PreAuthorize(VIEW)
    @Operation(summary = "Stock on hand that has not sold for the given number of days")
    public List<StockReportService.DeadLine> deadStock(
            @RequestParam UUID branchId,
            @RequestParam(defaultValue = "30") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate asOf) {
        access.requireFor(branchId);
        return stock.deadStock(branchId, positive(days), asOf == null ? today() : asOf);
    }

    @GetMapping("/stock/near-expiry")
    @PreAuthorize(VIEW)
    @Operation(summary = "Batches lapsing within the given number of days, at cost")
    public List<StockReportService.ExpiringLine> nearExpiry(
            @RequestParam UUID branchId,
            @RequestParam(defaultValue = "7") int days,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate asOf) {
        access.requireFor(branchId);
        return stock.nearExpiry(branchId, positive(days), asOf == null ? today() : asOf);
    }

    // --- export ---------------------------------------------------------------------------

    @GetMapping("/exports/{report}")
    @PreAuthorize("hasAuthority('export:data') and " + VIEW)
    @Operation(summary = "Any report as CSV or PDF; the same figures as the screen")
    public ResponseEntity<byte[]> export(
            @PathVariable String report,
            @RequestParam(defaultValue = "csv") String format,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(required = false) UUID categoryId,
            @RequestParam(defaultValue = "30") int days) {
        if (!ReportTables.REPORTS.contains(report)) {
            throw new Errors.NotFoundException("report.unknown", "No report called " + report);
        }
        ExportService.Table table =
                tables.build(report, filter(from, to, branchId, categoryId), positive(days));
        return file(table, format, report + "-" + from + "-" + to);
    }

    @GetMapping("/shifts/{shiftId}/export")
    @PreAuthorize("hasAuthority('export:data') and " + VIEW)
    @Operation(summary = "A Z- or X-report as CSV or PDF")
    public ResponseEntity<byte[]> exportShift(
            @PathVariable UUID shiftId, @RequestParam(defaultValue = "pdf") String format) {
        access.requireFor(shifts.forShift(shiftId).branchId());
        return file(tables.shift(shiftId), format, "shift-" + shiftId);
    }

    // --- operations -------------------------------------------------------------------------

    @PostMapping("/rebuild")
    @PreAuthorize("hasAuthority('report:view')")
    @Operation(
            summary =
                    "Throw the read models away and project them again from the event log; ingestion"
                            + " waits until it finishes")
    public RebuildService.Result rebuild() {
        return rebuild.rebuild();
    }

    @GetMapping("/lag")
    @PreAuthorize(VIEW)
    @Operation(summary = "How far behind the events the reports are")
    public LagMonitor.Lag lag() {
        return lag.current();
    }

    // --- helpers ------------------------------------------------------------------------

    private ReportFilter filter(LocalDate from, LocalDate to, UUID branchId, UUID categoryId) {
        access.requireFor(branchId);
        return new ReportFilter(from, to, branchId, categoryId);
    }

    private ResponseEntity<byte[]> file(ExportService.Table table, String format, String name) {
        return switch (format.toLowerCase()) {
            case "csv" ->
                    ResponseEntity.ok()
                            .contentType(
                                    new MediaType(
                                            "text", "csv", java.nio.charset.StandardCharsets.UTF_8))
                            .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name + ".csv"))
                            .body(exports.csv(table));
            case "pdf" ->
                    ResponseEntity.ok()
                            .contentType(MediaType.APPLICATION_PDF)
                            .header(HttpHeaders.CONTENT_DISPOSITION, attachment(name + ".pdf"))
                            .body(exports.pdf(table));
            default ->
                    throw new Errors.BadRequestException(
                            "report.unknown_format", "Export as csv or pdf");
        };
    }

    private static String attachment(String filename) {
        return ContentDisposition.attachment().filename(filename).build().toString();
    }

    private static int positive(int days) {
        if (days < 1 || days > 3650) {
            throw new Errors.BadRequestException(
                    "report.days_out_of_range", "Days must be between 1 and 3650");
        }
        return days;
    }

    private static LocalDate today() {
        return com.pos.reporting.domain.policy.BusinessDates.of(java.time.Instant.now());
    }
}
