package com.pos.reporting.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Stock at cost, stock that is not moving, and stock about to lapse.
 *
 * <p>Values come from inventory's own snapshots, and only a <b>complete</b> one counts: a snapshot
 * arrives in pages, and half a branch reported as its value would be worse than yesterday's whole
 * figure.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class StockReportService {

    private final JdbcClient jdbc;

    public record ValuationLine(
            UUID productId,
            String sku,
            String productName,
            String categoryCode,
            BigDecimal quantityOnHand,
            BigDecimal valueAtCost) {}

    public record Valuation(
            UUID snapshotId,
            UUID branchId,
            Instant valuedAt,
            BigDecimal totalValue,
            List<ValuationLine> lines) {}

    public record TrendPoint(LocalDate businessDate, UUID snapshotId, BigDecimal totalValue) {}

    public record DeadLine(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityOnHand,
            BigDecimal valueAtCost,
            LocalDate lastSold) {}

    public record ExpiringLine(
            UUID batchId,
            String batchNumber,
            UUID productId,
            String sku,
            String productName,
            LocalDate expiryDate,
            BigDecimal quantityRemaining,
            BigDecimal valueAtCost) {}

    /** The most recent complete snapshot for a branch, if inventory has sent one. */
    public Optional<Valuation> latestValuation(UUID branchId, UUID categoryId) {
        return latestCompleteSnapshot(branchId).map(id -> valuation(id, branchId, categoryId));
    }

    /** One point per day: that day's last complete snapshot. */
    public List<TrendPoint> valuationTrend(UUID branchId, LocalDate from, LocalDate to) {
        return jdbc.sql(
                        """
                        WITH complete AS (
                            SELECT snapshot_id, valued_at, SUM(value_at_cost) AS total
                            FROM report_stock_valuations
                            WHERE branch_id = :branch
                            GROUP BY snapshot_id, valued_at, page_count
                            HAVING count(DISTINCT page) = page_count
                        ),
                        dated AS (
                            SELECT snapshot_id, valued_at, total,
                                   (valued_at AT TIME ZONE 'Africa/Nairobi')::date AS day
                            FROM complete
                        )
                        SELECT DISTINCT ON (day) day, snapshot_id, total
                        FROM dated
                        WHERE day BETWEEN :from AND :to
                        ORDER BY day, valued_at DESC
                        """)
                .param("branch", branchId)
                .param("from", from)
                .param("to", to)
                .query(
                        (rs, row) ->
                                new TrendPoint(
                                        rs.getObject("day", LocalDate.class),
                                        rs.getObject("snapshot_id", UUID.class),
                                        ReportQueries.money(rs.getBigDecimal("total"))))
                .list();
    }

    /**
     * Stock on the shelf that has not sold for {@code days} days, as of {@code asOf}: money tied up
     * in things nobody is buying.
     */
    public List<DeadLine> deadStock(UUID branchId, int days, LocalDate asOf) {
        Optional<UUID> snapshot = latestCompleteSnapshot(branchId);
        if (snapshot.isEmpty()) {
            return List.of();
        }
        LocalDate since = asOf.minusDays(days);
        return jdbc.sql(
                        """
                        WITH last_sold AS (
                            SELECT l.product_id, MAX(s.business_date) AS last_sold
                            FROM report_sale_lines l
                            JOIN report_sales s ON s.sale_id = l.sale_id
                            LEFT JOIN report_sale_voids v ON v.sale_id = s.sale_id
                            WHERE v.sale_id IS NULL AND s.branch_id = :branch
                              AND s.business_date <= :asOf
                            GROUP BY l.product_id
                        )
                        SELECT sv.product_id, sv.sku, p.name, sv.quantity_on_hand,
                               sv.value_at_cost, ls.last_sold
                        FROM report_stock_valuations sv
                        LEFT JOIN last_sold ls ON ls.product_id = sv.product_id
                        LEFT JOIN report_products p ON p.product_id = sv.product_id
                        WHERE sv.snapshot_id = :snapshot AND sv.quantity_on_hand > 0
                          AND (ls.last_sold IS NULL OR ls.last_sold < :since)
                        ORDER BY sv.value_at_cost DESC, sv.sku
                        """)
                .param("branch", branchId)
                .param("snapshot", snapshot.get())
                .param("asOf", asOf)
                .param("since", since)
                .query(
                        (rs, row) ->
                                new DeadLine(
                                        rs.getObject("product_id", UUID.class),
                                        rs.getString("sku"),
                                        rs.getString("name"),
                                        rs.getBigDecimal("quantity_on_hand"),
                                        ReportQueries.money(rs.getBigDecimal("value_at_cost")),
                                        rs.getObject("last_sold", LocalDate.class)))
                .list();
    }

    /** Batches lapsing on or before {@code asOf + withinDays}, and what they are worth. */
    public List<ExpiringLine> nearExpiry(UUID branchId, int withinDays, LocalDate asOf) {
        return jdbc.sql(
                        """
                        SELECT * FROM report_expiring_batches
                        WHERE branch_id = :branch AND quantity_remaining > 0
                          AND expiry_date <= :cutoff
                        ORDER BY expiry_date, value_at_cost DESC
                        """)
                .param("branch", branchId)
                .param("cutoff", asOf.plusDays(withinDays))
                .query(
                        (rs, row) ->
                                new ExpiringLine(
                                        rs.getObject("batch_id", UUID.class),
                                        rs.getString("batch_number"),
                                        rs.getObject("product_id", UUID.class),
                                        rs.getString("sku"),
                                        rs.getString("product_name"),
                                        rs.getObject("expiry_date", LocalDate.class),
                                        rs.getBigDecimal("quantity_remaining"),
                                        ReportQueries.money(rs.getBigDecimal("value_at_cost"))))
                .list();
    }

    // --- helpers ------------------------------------------------------------------------

    private Optional<UUID> latestCompleteSnapshot(UUID branchId) {
        return jdbc.sql(
                        """
                        SELECT snapshot_id FROM report_stock_valuations
                        WHERE branch_id = :branch
                        GROUP BY snapshot_id, valued_at, page_count
                        HAVING count(DISTINCT page) = page_count
                        ORDER BY valued_at DESC
                        LIMIT 1
                        """)
                .param("branch", branchId)
                .query(UUID.class)
                .optional();
    }

    private Valuation valuation(UUID snapshotId, UUID branchId, UUID categoryId) {
        String categoryCondition = categoryId == null ? "" : " AND p.category_id = :category";
        var statement =
                jdbc.sql(
                                """
                                SELECT sv.product_id, sv.sku, p.name, sv.quantity_on_hand,
                                       sv.value_at_cost, sv.valued_at,
                                       COALESCE(p.category_code, '%s') AS category_code
                                FROM report_stock_valuations sv
                                LEFT JOIN report_products p ON p.product_id = sv.product_id
                                WHERE sv.snapshot_id = :snapshot%s
                                ORDER BY sv.value_at_cost DESC, sv.sku
                                """
                                        .formatted(ReportQueries.UNCATEGORISED, categoryCondition))
                        .param("snapshot", snapshotId);
        if (categoryId != null) {
            statement = statement.param("category", categoryId);
        }
        Instant[] valuedAt = new Instant[1];
        List<ValuationLine> lines =
                statement
                        .query(
                                (rs, row) -> {
                                    valuedAt[0] = rs.getTimestamp("valued_at").toInstant();
                                    return new ValuationLine(
                                            rs.getObject("product_id", UUID.class),
                                            rs.getString("sku"),
                                            rs.getString("name"),
                                            rs.getString("category_code"),
                                            rs.getBigDecimal("quantity_on_hand"),
                                            ReportQueries.money(rs.getBigDecimal("value_at_cost")));
                                })
                        .list();
        BigDecimal total =
                lines.stream()
                        .map(ValuationLine::valueAtCost)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return new Valuation(snapshotId, branchId, valuedAt[0], ReportQueries.money(total), lines);
    }
}
