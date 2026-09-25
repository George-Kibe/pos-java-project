package com.pos.reporting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.ResultSet;
import java.sql.SQLException;
import java.time.LocalDate;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.reporting.domain.policy.BusinessDates;

import lombok.RequiredArgsConstructor;

/**
 * The aggregates, computed from the facts when asked.
 *
 * <p>Three conventions hold across every report, so their numbers agree with each other:
 *
 * <ul>
 *   <li>A <b>voided</b> sale is not a sale: it is left out of revenue, baskets and margin
 *       everywhere. (The shift report still shows it, because the drawer saw it.)
 *   <li>A <b>return</b> counts on the day it happened, not the day of the sale: that is the day the
 *       money went back. Its net and cost are the original line's, in proportion to the quantity
 *       returned - and cost comes back only when the goods were resaleable, because damaged goods
 *       are a loss, not stock.
 *   <li><b>Revenue</b> is net of tax. Tax is the government's money passing through.
 * </ul>
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReportQueries {

    public static final String UNCATEGORISED = "UNCATEGORISED";

    private final JdbcClient jdbc;

    // --- result shapes --------------------------------------------------------------

    public record SalesRow(
            LocalDate businessDate,
            UUID branchId,
            UUID cashierId,
            long baskets,
            BigDecimal grossSales,
            BigDecimal netSales,
            BigDecimal tax,
            BigDecimal averageBasket) {}

    public record ProductRow(
            UUID productId,
            String sku,
            String productName,
            String categoryCode,
            BigDecimal quantitySold,
            BigDecimal quantityReturned,
            BigDecimal netSales,
            BigDecimal cost,
            BigDecimal margin,
            BigDecimal marginPercent,
            /**
             * Sold with no known cost: ahead of its delivery, or before inventory reported the
             * deduction. Where this is not zero, the cost is short and the margin flattered.
             */
            BigDecimal uncostedQuantity) {}

    public record CategoryRow(
            String categoryCode,
            BigDecimal netSales,
            BigDecimal cost,
            BigDecimal margin,
            BigDecimal marginPercent,
            /** Units across the category sold with no known cost; see {@link ProductRow}. */
            BigDecimal uncostedQuantity) {}

    public record TenderRow(String method, long tenders, BigDecimal amount, BigDecimal share) {}

    /**
     * An hour of the shop's day, across the range: how busy it was and how big the baskets were.
     *
     * @param hour 0-23 in the shop's time zone
     * @param items units sold, weighed goods by weight
     */
    public record HourRow(
            int hour,
            long baskets,
            BigDecimal netSales,
            BigDecimal averageBasket,
            BigDecimal items,
            BigDecimal itemsPerBasket) {}

    /**
     * Stock lost, by reason and product: written off, or found missing by a count. Quantity and
     * value are positive figures of what was lost.
     */
    public record ShrinkageRow(
            String reasonCode,
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            BigDecimal valueAtCost) {}

    // --- sales ------------------------------------------------------------------------

    /** One row per day and branch. */
    public List<SalesRow> salesDaily(ReportFilter filter) {
        return sales(filter, "s.business_date", "s.branch_id", "NULL::uuid");
    }

    /** A calendar period to roll business days up to. Weeks start on Monday (ISO). */
    public enum Period {
        WEEK("week"),
        MONTH("month"),
        QUARTER("quarter"),
        YEAR("year");

        private final String unit;

        Period(String unit) {
            this.unit = unit;
        }
    }

    /**
     * One row per period - and per branch, unless {@code acrossBranches} rolls them together - with
     * the period's first day as its date. The unit comes from the enum, never from the caller, so
     * it is safe in the SQL text.
     */
    public List<SalesRow> salesByPeriod(
            ReportFilter filter, Period period, boolean acrossBranches) {
        return sales(
                filter,
                "date_trunc('%s', s.business_date)::date".formatted(period.unit),
                acrossBranches ? "NULL::uuid" : "s.branch_id",
                "NULL::uuid");
    }

    /** One row per branch over the range. */
    public List<SalesRow> salesByBranch(ReportFilter filter) {
        return sales(filter, "NULL::date", "s.branch_id", "NULL::uuid");
    }

    /** One row per cashier and branch over the range. */
    public List<SalesRow> salesByCashier(ReportFilter filter) {
        return sales(filter, "NULL::date", "s.branch_id", "s.cashier_id");
    }

    /**
     * @param day the day key column, or a typed NULL to roll days up
     * @param branch the branch key column
     * @param cashier the cashier key column, or a typed NULL
     */
    private List<SalesRow> sales(ReportFilter filter, String day, String branch, String cashier) {
        ReportFilter.Where where = filter.where("s.business_date", "s.branch_id");
        String sql =
                """
                SELECT %1$s AS k_day, %2$s AS k_branch, %3$s AS k_cashier,
                       count(*) AS baskets,
                       COALESCE(SUM(s.grand_total), 0) AS gross,
                       COALESCE(SUM(s.net_total), 0) AS net,
                       COALESCE(SUM(s.tax_total), 0) AS tax
                FROM report_sales s
                LEFT JOIN report_sale_voids v ON v.sale_id = s.sale_id
                WHERE v.sale_id IS NULL AND %4$s
                GROUP BY 1, 2, 3
                ORDER BY 1 NULLS FIRST, 2, 3 NULLS FIRST
                """
                        .formatted(day, branch, cashier, where.sql());
        return jdbc.sql(sql)
                .params(where.params)
                .query(
                        (rs, row) -> {
                            long baskets = rs.getLong("baskets");
                            BigDecimal gross = rs.getBigDecimal("gross");
                            return new SalesRow(
                                    rs.getObject("k_day", LocalDate.class),
                                    rs.getObject("k_branch", UUID.class),
                                    rs.getObject("k_cashier", UUID.class),
                                    baskets,
                                    money(gross),
                                    money(rs.getBigDecimal("net")),
                                    money(rs.getBigDecimal("tax")),
                                    baskets == 0
                                            ? money(BigDecimal.ZERO)
                                            : gross.divide(
                                                    BigDecimal.valueOf(baskets),
                                                    4,
                                                    RoundingMode.HALF_UP));
                        })
                .list();
    }

    /**
     * Sales by hour of the shop's day, voids excluded. Items per basket counts every unit, so a
     * kilo of bananas is one.
     */
    public List<HourRow> salesByHour(ReportFilter filter) {
        ReportFilter.Where where = filter.where("s.business_date", "s.branch_id");
        where.params.put("zone", BusinessDates.SHOP_ZONE.getId());
        return jdbc.sql(
                        """
                        SELECT EXTRACT(HOUR FROM s.completed_at AT TIME ZONE :zone)::int AS hour,
                               count(*) AS baskets,
                               COALESCE(SUM(s.net_total), 0) AS net,
                               COALESCE(SUM(s.grand_total), 0) AS gross,
                               COALESCE(SUM(l.items), 0) AS items
                        FROM report_sales s
                        LEFT JOIN report_sale_voids v ON v.sale_id = s.sale_id
                        LEFT JOIN (SELECT sale_id, SUM(quantity) AS items
                                   FROM report_sale_lines GROUP BY sale_id) l
                               ON l.sale_id = s.sale_id
                        WHERE v.sale_id IS NULL AND %s
                        GROUP BY 1
                        ORDER BY 1
                        """
                                .formatted(where.sql()))
                .params(where.params)
                .query(
                        (rs, row) -> {
                            long baskets = rs.getLong("baskets");
                            BigDecimal items = rs.getBigDecimal("items");
                            return new HourRow(
                                    rs.getInt("hour"),
                                    baskets,
                                    money(rs.getBigDecimal("net")),
                                    baskets == 0
                                            ? money(BigDecimal.ZERO)
                                            : rs.getBigDecimal("gross")
                                                    .divide(
                                                            BigDecimal.valueOf(baskets),
                                                            4,
                                                            RoundingMode.HALF_UP),
                                    items.setScale(3, RoundingMode.HALF_UP),
                                    baskets == 0
                                            ? BigDecimal.ZERO.setScale(3)
                                            : items.divide(
                                                    BigDecimal.valueOf(baskets),
                                                    3,
                                                    RoundingMode.HALF_UP));
                        })
                .list();
    }

    /**
     * What went missing: stock taken off by a write-off or found short by a count, by reason and
     * product, largest loss first. Stock put back on is not shrinkage and is left out.
     */
    public List<ShrinkageRow> shrinkage(ReportFilter filter) {
        ReportFilter.Where where = filter.where("a.business_date", "a.branch_id");
        if (filter.categoryId() != null) {
            where.add("p.category_id = :category", "category", filter.categoryId());
        }
        return jdbc.sql(
                        """
                        SELECT a.reason_code, a.product_id, max(a.sku) AS sku, max(p.name) AS name,
                               -SUM(a.quantity_delta) AS quantity,
                               -SUM(a.value_at_cost) AS value
                        FROM report_stock_adjustments a
                        LEFT JOIN report_products p ON p.product_id = a.product_id
                        WHERE a.quantity_delta < 0 AND %s
                        GROUP BY a.reason_code, a.product_id
                        ORDER BY value DESC, a.reason_code
                        """
                                .formatted(where.sql()))
                .params(where.params)
                .query(
                        (rs, row) ->
                                new ShrinkageRow(
                                        rs.getString("reason_code"),
                                        rs.getObject("product_id", UUID.class),
                                        rs.getString("sku"),
                                        rs.getString("name"),
                                        rs.getBigDecimal("quantity")
                                                .setScale(3, RoundingMode.HALF_UP),
                                        money(rs.getBigDecimal("value"))))
                .list();
    }

    /** Refunds paid in the range. */
    public BigDecimal returnsTotal(ReportFilter filter) {
        ReportFilter.Where where = filter.where("r.business_date", "r.branch_id");
        return jdbc.sql(
                        "SELECT COALESCE(SUM(r.refund_total), 0) FROM report_returns r WHERE "
                                + where.sql())
                .params(where.params)
                .query(BigDecimal.class)
                .single();
    }

    // --- products, categories, margin -------------------------------------------------

    /**
     * Product by product, net of what came back, with cost and margin.
     *
     * <p>Filtered to a category when the filter names one; products catalog never described are
     * grouped as {@value #UNCATEGORISED} rather than dropped.
     */
    public List<ProductRow> salesByProduct(ReportFilter filter) {
        ReportFilter.Where sold = filter.where("s.business_date", "s.branch_id");
        ReportFilter.Where back = filter.where("r.business_date", "r.branch_id");
        // Distinct parameter names for the second range.
        String backSql =
                back.sql()
                        .replace(":from", ":rfrom")
                        .replace(":to", ":rto")
                        .replace(":branch", ":rbranch");
        Map<String, Object> params = new java.util.LinkedHashMap<>(sold.params);
        params.put("rfrom", filter.from());
        params.put("rto", filter.to());
        if (filter.branchId() != null) {
            params.put("rbranch", filter.branchId());
        }
        String categoryCondition = "";
        if (filter.categoryId() != null) {
            categoryCondition = "WHERE p.category_id = :category";
            params.put("category", filter.categoryId());
        }

        String sql =
                """
                WITH sold AS (
                    SELECT l.product_id,
                           MAX(l.sku) AS sku,
                           MAX(l.product_name) AS product_name,
                           SUM(l.quantity) AS quantity,
                           SUM(l.line_total - l.tax_amount) AS net
                    FROM report_sale_lines l
                    JOIN report_sales s ON s.sale_id = l.sale_id
                    LEFT JOIN report_sale_voids v ON v.sale_id = s.sale_id
                    WHERE v.sale_id IS NULL AND %s
                    GROUP BY l.product_id
                ),
                costed AS (
                    SELECT c.product_id, SUM(c.cost) AS cost,
                           SUM(c.costed_quantity) AS costed_quantity
                    FROM report_sale_costs c
                    JOIN report_sales s ON s.sale_id = c.sale_id
                    LEFT JOIN report_sale_voids v ON v.sale_id = s.sale_id
                    WHERE v.sale_id IS NULL AND %s
                    GROUP BY c.product_id
                ),
                line_unit AS (
                    -- Per sale and product: what one unit was charged at, and cost.
                    SELECT l.sale_id, l.product_id,
                           SUM(l.line_total - l.tax_amount) / NULLIF(SUM(l.quantity), 0)
                               AS net_per_unit
                    FROM report_sale_lines l
                    GROUP BY l.sale_id, l.product_id
                ),
                returned AS (
                    SELECT rl.product_id,
                           SUM(rl.quantity) AS quantity,
                           SUM(rl.quantity * COALESCE(u.net_per_unit, 0)) AS net,
                           SUM(CASE WHEN rl.resaleable
                                    THEN rl.quantity
                                         * COALESCE(c.cost / NULLIF(c.costed_quantity, 0), 0)
                                    ELSE 0 END) AS cost
                    FROM report_return_lines rl
                    JOIN report_returns r ON r.return_id = rl.return_id
                    LEFT JOIN line_unit u
                        ON u.sale_id = r.sale_id AND u.product_id = rl.product_id
                    LEFT JOIN report_sale_costs c
                        ON c.sale_id = r.sale_id AND c.product_id = rl.product_id
                    WHERE %s
                    GROUP BY rl.product_id
                ),
                products AS (
                    SELECT product_id FROM sold
                    UNION
                    SELECT product_id FROM returned
                )
                SELECT pr.product_id,
                       COALESCE(so.sku, p.sku) AS sku,
                       COALESCE(so.product_name, p.name) AS product_name,
                       COALESCE(p.category_code, '%s') AS category_code,
                       COALESCE(so.quantity, 0) AS quantity_sold,
                       COALESCE(re.quantity, 0) AS quantity_returned,
                       COALESCE(so.net, 0) - COALESCE(re.net, 0) AS net,
                       COALESCE(co.cost, 0) - COALESCE(re.cost, 0) AS cost,
                       -- Not yet deducted counts as uncosted too: no cost is known for it.
                       GREATEST(COALESCE(so.quantity, 0) - COALESCE(co.costed_quantity, 0), 0)
                           AS uncosted_quantity
                FROM products pr
                LEFT JOIN sold so ON so.product_id = pr.product_id
                LEFT JOIN returned re ON re.product_id = pr.product_id
                LEFT JOIN costed co ON co.product_id = pr.product_id
                LEFT JOIN report_products p ON p.product_id = pr.product_id
                %s
                ORDER BY net DESC, sku
                """
                        .formatted(
                                sold.sql(), sold.sql(), backSql, UNCATEGORISED, categoryCondition);

        return jdbc.sql(sql).params(params).query(this::productRow).list();
    }

    private ProductRow productRow(ResultSet rs, int row) throws SQLException {
        BigDecimal net = money(rs.getBigDecimal("net"));
        BigDecimal cost = money(rs.getBigDecimal("cost"));
        BigDecimal margin = net.subtract(cost);
        return new ProductRow(
                rs.getObject("product_id", UUID.class),
                rs.getString("sku"),
                rs.getString("product_name"),
                rs.getString("category_code"),
                rs.getBigDecimal("quantity_sold"),
                rs.getBigDecimal("quantity_returned"),
                net,
                cost,
                margin,
                percent(margin, net),
                rs.getBigDecimal("uncosted_quantity"));
    }

    /** Margin rolled up to category - the view a buyer manages the range by. */
    public List<CategoryRow> marginByCategory(ReportFilter filter) {
        Map<String, BigDecimal[]> byCategory = new java.util.TreeMap<>();
        for (ProductRow row : salesByProduct(filter)) {
            BigDecimal[] sums =
                    byCategory.computeIfAbsent(
                            row.categoryCode(),
                            key ->
                                    new BigDecimal[] {
                                        BigDecimal.ZERO, BigDecimal.ZERO, BigDecimal.ZERO
                                    });
            sums[0] = sums[0].add(row.netSales());
            sums[1] = sums[1].add(row.cost());
            sums[2] = sums[2].add(row.uncostedQuantity());
        }
        return byCategory.entrySet().stream()
                .map(
                        entry -> {
                            BigDecimal net = entry.getValue()[0];
                            BigDecimal cost = entry.getValue()[1];
                            BigDecimal margin = net.subtract(cost);
                            return new CategoryRow(
                                    entry.getKey(),
                                    net,
                                    cost,
                                    margin,
                                    percent(margin, net),
                                    entry.getValue()[2]);
                        })
                .toList();
    }

    // --- payment mix --------------------------------------------------------------------

    public List<TenderRow> paymentMix(ReportFilter filter) {
        ReportFilter.Where where = filter.where("s.business_date", "s.branch_id");
        List<Object[]> rows =
                jdbc.sql(
                                """
                                SELECT t.method, count(*) AS tenders,
                                       COALESCE(SUM(t.amount), 0) AS amount
                                FROM report_sale_tenders t
                                JOIN report_sales s ON s.sale_id = t.sale_id
                                LEFT JOIN report_sale_voids v ON v.sale_id = s.sale_id
                                WHERE v.sale_id IS NULL AND %s
                                GROUP BY t.method
                                ORDER BY amount DESC, t.method
                                """
                                        .formatted(where.sql()))
                        .params(where.params)
                        .query(
                                (rs, row) ->
                                        new Object[] {
                                            rs.getString("method"),
                                            rs.getLong("tenders"),
                                            rs.getBigDecimal("amount")
                                        })
                        .list();
        BigDecimal total =
                rows.stream()
                        .map(row -> (BigDecimal) row[2])
                        .reduce(BigDecimal.ZERO, BigDecimal::add);
        return rows.stream()
                .map(
                        row ->
                                new TenderRow(
                                        (String) row[0],
                                        (Long) row[1],
                                        (BigDecimal) row[2],
                                        percent((BigDecimal) row[2], total)))
                .toList();
    }

    // --- helpers ------------------------------------------------------------------------

    static BigDecimal money(BigDecimal value) {
        return (value == null ? BigDecimal.ZERO : value).setScale(4, RoundingMode.HALF_UP);
    }

    /** A share as a percentage to two places; zero when there is nothing to share. */
    static BigDecimal percent(BigDecimal part, BigDecimal whole) {
        if (whole == null || whole.signum() == 0) {
            return BigDecimal.ZERO.setScale(2);
        }
        return part.multiply(BigDecimal.valueOf(100)).divide(whole, 2, RoundingMode.HALF_UP);
    }
}
