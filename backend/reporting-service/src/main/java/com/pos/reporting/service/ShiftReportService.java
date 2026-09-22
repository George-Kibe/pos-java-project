package com.pos.reporting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.reporting.domain.policy.ShiftArithmetic;

import lombok.RequiredArgsConstructor;

/**
 * Z- and X-reports.
 *
 * <p>A Z-report is a closed shift, worked out from the sales, voids and refunds reporting saw and
 * set against the figures the till itself reported at close - to the cent. A disagreement is not
 * rounded away: it is listed, figure by figure, because it means one side missed something. An
 * X-report is the same arithmetic on a shift still open: what the drawer should hold so far, less
 * any drops, which the till only reports when it closes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ShiftReportService {

    private final JdbcClient jdbc;

    public record ShiftReport(
            UUID shiftId,
            UUID branchId,
            UUID registerId,
            UUID cashierId,
            String kind,
            Instant openedAt,
            Instant closedAt,
            ShiftArithmetic.Totals totals,
            ShiftArithmetic.TillFigures till,
            BigDecimal expectedCash,
            List<ShiftArithmetic.Difference> differences,
            boolean reconciled,
            String currency) {}

    /** Every shift a branch closed on a business day, with their sum. */
    public record BranchDay(
            UUID branchId,
            LocalDate businessDate,
            List<ShiftReport> shifts,
            ShiftArithmetic.Totals totals,
            BigDecimal countedCash,
            BigDecimal variance,
            boolean reconciled) {}

    private record ShiftFacts(
            UUID shiftId,
            UUID branchId,
            UUID registerId,
            UUID cashierId,
            Instant openedAt,
            Instant closedAt,
            ShiftArithmetic.TillFigures till,
            String currency) {}

    public ShiftReport forShift(UUID shiftId) {
        Optional<ShiftFacts> closed = closedShift(shiftId);
        List<ShiftArithmetic.Sale> sales = salesOn(shiftId);
        List<ShiftArithmetic.Refund> refunds = refundsFrom(shiftId);
        ShiftArithmetic.Totals totals = ShiftArithmetic.totals(sales, refunds);

        if (closed.isPresent()) {
            ShiftFacts facts = closed.get();
            List<ShiftArithmetic.Difference> differences =
                    ShiftArithmetic.reconcile(totals, facts.till());
            return new ShiftReport(
                    shiftId,
                    facts.branchId(),
                    facts.registerId(),
                    facts.cashierId(),
                    "Z",
                    facts.openedAt(),
                    facts.closedAt(),
                    totals,
                    facts.till(),
                    ShiftArithmetic.expectedCash(
                            facts.till().openingFloat(), totals, facts.till().cashDrops()),
                    differences,
                    differences.isEmpty(),
                    facts.currency());
        }

        Map<String, Object> open = openShiftIdentity(shiftId);
        return new ShiftReport(
                shiftId,
                (UUID) open.get("branch_id"),
                (UUID) open.get("register_id"),
                (UUID) open.get("cashier_id"),
                "X",
                null,
                null,
                totals,
                null,
                // No float or drops until the till reports them at close: cash taken, less out.
                totals.cashSales().subtract(totals.cashRefunds()),
                List.of(),
                false,
                (String) open.get("currency"));
    }

    /** The branch's day: every shift closed on it, and whether all of them reconcile. */
    public BranchDay forBranchDay(UUID branchId, LocalDate day) {
        List<UUID> shiftIds =
                jdbc.sql(
                                """
                                SELECT shift_id FROM report_shifts
                                WHERE branch_id = :branch AND business_date = :day
                                ORDER BY closed_at
                                """)
                        .param("branch", branchId)
                        .param("day", day)
                        .query(UUID.class)
                        .list();
        List<ShiftReport> shifts = new ArrayList<>();
        List<ShiftArithmetic.Sale> allSales = new ArrayList<>();
        List<ShiftArithmetic.Refund> allRefunds = new ArrayList<>();
        BigDecimal counted = BigDecimal.ZERO;
        BigDecimal variance = BigDecimal.ZERO;
        for (UUID shiftId : shiftIds) {
            ShiftReport report = forShift(shiftId);
            shifts.add(report);
            allSales.addAll(salesOn(shiftId));
            allRefunds.addAll(refundsFrom(shiftId));
            counted = counted.add(report.till().countedCash());
            variance = variance.add(report.till().variance());
        }
        return new BranchDay(
                branchId,
                day,
                shifts,
                ShiftArithmetic.totals(allSales, allRefunds),
                counted,
                variance,
                shifts.stream().allMatch(ShiftReport::reconciled));
    }

    // --- facts ------------------------------------------------------------------------

    private Optional<ShiftFacts> closedShift(UUID shiftId) {
        return jdbc.sql("SELECT * FROM report_shifts WHERE shift_id = :shift")
                .param("shift", shiftId)
                .query(
                        (rs, row) ->
                                new ShiftFacts(
                                        rs.getObject("shift_id", UUID.class),
                                        rs.getObject("branch_id", UUID.class),
                                        rs.getObject("register_id", UUID.class),
                                        rs.getObject("cashier_id", UUID.class),
                                        instant(rs.getTimestamp("opened_at")),
                                        instant(rs.getTimestamp("closed_at")),
                                        new ShiftArithmetic.TillFigures(
                                                rs.getBigDecimal("opening_float"),
                                                rs.getBigDecimal("cash_sales"),
                                                rs.getBigDecimal("cash_refunds"),
                                                rs.getBigDecimal("cash_drops"),
                                                rs.getBigDecimal("non_cash_sales"),
                                                rs.getInt("sale_count"),
                                                rs.getBigDecimal("expected_cash"),
                                                rs.getBigDecimal("counted_cash"),
                                                rs.getBigDecimal("variance")),
                                        rs.getString("currency")))
                .optional();
    }

    /** Branch and cashier of a shift still open, from its sales. */
    private Map<String, Object> openShiftIdentity(UUID shiftId) {
        List<Map<String, Object>> rows =
                jdbc.sql(
                                """
                                SELECT branch_id, register_id, cashier_id, currency
                                FROM report_sales WHERE shift_id = :shift
                                ORDER BY completed_at LIMIT 1
                                """)
                        .param("shift", shiftId)
                        .query()
                        .listOfRows();
        if (rows.isEmpty()) {
            List<Map<String, Object>> refunds =
                    jdbc.sql(
                                    """
                                    SELECT branch_id, NULL::uuid AS register_id,
                                           NULL::uuid AS cashier_id, currency
                                    FROM report_returns WHERE till_session_id = :shift LIMIT 1
                                    """)
                            .param("shift", shiftId)
                            .query()
                            .listOfRows();
            if (refunds.isEmpty()) {
                throw Errors.NotFoundException.of("Shift", shiftId);
            }
            return refunds.getFirst();
        }
        return rows.getFirst();
    }

    private List<ShiftArithmetic.Sale> salesOn(UUID shiftId) {
        Map<UUID, List<ShiftArithmetic.Tender>> tenders = new LinkedHashMap<>();
        jdbc.sql(
                        """
                        SELECT t.sale_id, t.method, t.amount
                        FROM report_sale_tenders t
                        JOIN report_sales s ON s.sale_id = t.sale_id
                        WHERE s.shift_id = :shift
                        ORDER BY t.sale_id, t.ordinal
                        """)
                .param("shift", shiftId)
                .query(
                        (rs, row) -> {
                            tenders.computeIfAbsent(
                                            rs.getObject("sale_id", UUID.class),
                                            key -> new ArrayList<>())
                                    .add(
                                            new ShiftArithmetic.Tender(
                                                    rs.getString("method"),
                                                    rs.getBigDecimal("amount")));
                            return null;
                        })
                .list();

        return jdbc.sql(
                        """
                        SELECT s.sale_id, s.grand_total, (v.sale_id IS NOT NULL) AS voided
                        FROM report_sales s
                        LEFT JOIN report_sale_voids v ON v.sale_id = s.sale_id
                        WHERE s.shift_id = :shift
                        ORDER BY s.completed_at, s.sale_id
                        """)
                .param("shift", shiftId)
                .query(
                        (rs, row) ->
                                new ShiftArithmetic.Sale(
                                        rs.getBigDecimal("grand_total"),
                                        tenders.getOrDefault(
                                                rs.getObject("sale_id", UUID.class), List.of()),
                                        rs.getBoolean("voided")))
                .list();
    }

    private List<ShiftArithmetic.Refund> refundsFrom(UUID shiftId) {
        return jdbc.sql(
                        """
                        SELECT refund_method, refund_total FROM report_returns
                        WHERE till_session_id = :shift ORDER BY processed_at, return_id
                        """)
                .param("shift", shiftId)
                .query(
                        (rs, row) ->
                                new ShiftArithmetic.Refund(
                                        rs.getString("refund_method"),
                                        rs.getBigDecimal("refund_total")))
                .list();
    }

    private static Instant instant(java.sql.Timestamp timestamp) {
        return timestamp == null ? null : timestamp.toInstant();
    }
}
