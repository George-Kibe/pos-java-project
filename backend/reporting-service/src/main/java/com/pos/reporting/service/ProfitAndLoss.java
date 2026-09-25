package com.pos.reporting.service;

import java.math.BigDecimal;
import java.sql.Date;
import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import lombok.RequiredArgsConstructor;

/**
 * Profit and loss: what was sold, less what it cost, less what was lost, less what running the
 * shops cost.
 *
 * <p>Built on the margin and shrinkage reports rather than beside them, so the same sale or
 * write-off gives the same figure in all three. Everything is without VAT: sales net of output VAT,
 * costs net of input VAT.
 *
 * <ul>
 *   <li><b>Net sales</b> and <b>cost of sales</b>: as the margin report - after returns, cost at
 *       the batches the goods came from.
 *   <li><b>Losses</b>: as the shrinkage report - write-offs and count shortfalls at cost.
 *   <li><b>Expenses</b>: approved ones only. Per item there are none: rent cannot honestly be put
 *       on a bag of flour, so a product stops at profit after losses. Head office's count once, in
 *       the business-wide figure, never shared out over the branches.
 * </ul>
 */
@Service
@RequiredArgsConstructor
public class ProfitAndLoss {

    private final ReportQueries queries;
    private final JdbcClient jdbc;

    /** One named amount: a loss by reason, an expense by category. */
    public record Amount(String code, BigDecimal amount) {}

    /**
     * A branch's statement, or the business's when {@code branchId} is null.
     *
     * @param uncostedQuantity units sold with no known cost; where not zero, cost of sales is short
     *     and the profit flattered
     */
    public record Statement(
            UUID branchId,
            BigDecimal netSales,
            BigDecimal costOfSales,
            BigDecimal grossProfit,
            BigDecimal grossMarginPercent,
            List<Amount> losses,
            BigDecimal lossTotal,
            BigDecimal profitAfterLosses,
            List<Amount> expenses,
            BigDecimal expenseTotal,
            BigDecimal netProfit,
            BigDecimal netMarginPercent,
            BigDecimal uncostedQuantity) {}

    /**
     * @param branches one statement per branch with anything in the period
     * @param headOfficeExpenses shown only for the whole business
     * @param total the business, or the one branch asked about
     */
    public record Report(
            LocalDate from,
            LocalDate to,
            List<Statement> branches,
            List<Amount> headOfficeExpenses,
            BigDecimal headOfficeTotal,
            Statement total) {}

    /** A product's contribution: gross profit, less what of it was lost. */
    public record ProductProfit(
            UUID productId,
            String sku,
            String productName,
            String categoryCode,
            BigDecimal quantitySold,
            BigDecimal netSales,
            BigDecimal costOfSales,
            BigDecimal grossProfit,
            BigDecimal losses,
            BigDecimal profitAfterLosses,
            BigDecimal marginPercent,
            BigDecimal uncostedQuantity) {}

    @Transactional(readOnly = true)
    public Report statement(ReportFilter filter) {
        List<Statement> branches = new ArrayList<>();
        for (UUID branch : branchesWithActivity(filter)) {
            branches.add(branchStatement(filter, branch));
        }
        List<Amount> headOffice =
                filter.branchId() == null ? expensesByCategory(filter, null) : List.of();
        BigDecimal headOfficeTotal = sum(headOffice);

        Statement total;
        if (filter.branchId() != null) {
            total =
                    branches.isEmpty()
                            ? branchStatement(filter, filter.branchId())
                            : branches.getFirst();
        } else {
            total = combined(branches, headOffice);
        }
        return new Report(filter.from(), filter.to(), branches, headOffice, headOfficeTotal, total);
    }

    /** Product by product; a product with losses but no sales is there too. */
    @Transactional(readOnly = true)
    public List<ProductProfit> byProduct(ReportFilter filter) {
        Map<UUID, BigDecimal> lost = new LinkedHashMap<>();
        Map<UUID, String[]> lostNames = new LinkedHashMap<>();
        for (ReportQueries.ShrinkageRow row : queries.shrinkage(filter)) {
            lost.merge(row.productId(), row.valueAtCost(), BigDecimal::add);
            lostNames.putIfAbsent(row.productId(), new String[] {row.sku(), row.productName()});
        }
        List<ProductProfit> rows = new ArrayList<>();
        for (ReportQueries.ProductRow row : queries.salesByProduct(filter)) {
            BigDecimal losses = ReportQueries.money(lost.remove(row.productId()));
            BigDecimal gross = row.netSales().subtract(row.cost());
            BigDecimal after = gross.subtract(losses);
            rows.add(
                    new ProductProfit(
                            row.productId(),
                            row.sku(),
                            row.productName(),
                            row.categoryCode(),
                            row.quantitySold(),
                            row.netSales(),
                            row.cost(),
                            gross,
                            losses,
                            after,
                            ReportQueries.percent(after, row.netSales()),
                            row.uncostedQuantity()));
        }
        lost.forEach(
                (productId, value) -> {
                    BigDecimal losses = ReportQueries.money(value);
                    String[] names = lostNames.get(productId);
                    rows.add(
                            new ProductProfit(
                                    productId,
                                    names[0],
                                    names[1],
                                    null,
                                    BigDecimal.ZERO,
                                    ReportQueries.money(BigDecimal.ZERO),
                                    ReportQueries.money(BigDecimal.ZERO),
                                    ReportQueries.money(BigDecimal.ZERO),
                                    losses,
                                    losses.negate(),
                                    ReportQueries.percent(BigDecimal.ZERO, BigDecimal.ZERO),
                                    BigDecimal.ZERO));
                });
        rows.sort((a, b) -> b.profitAfterLosses().compareTo(a.profitAfterLosses()));
        return rows;
    }

    private Statement branchStatement(ReportFilter filter, UUID branch) {
        ReportFilter one = new ReportFilter(filter.from(), filter.to(), branch, null);
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal uncosted = BigDecimal.ZERO;
        for (ReportQueries.ProductRow row : queries.salesByProduct(one)) {
            net = net.add(row.netSales());
            cost = cost.add(row.cost());
            uncosted = uncosted.add(row.uncostedQuantity());
        }
        Map<String, BigDecimal> byReason = new TreeMap<>();
        for (ReportQueries.ShrinkageRow row : queries.shrinkage(one)) {
            byReason.merge(row.reasonCode(), row.valueAtCost(), BigDecimal::add);
        }
        List<Amount> losses = amounts(byReason);
        return statement(branch, net, cost, losses, expensesByCategory(one, branch), uncosted);
    }

    private static Statement combined(List<Statement> branches, List<Amount> headOffice) {
        BigDecimal net = BigDecimal.ZERO;
        BigDecimal cost = BigDecimal.ZERO;
        BigDecimal uncosted = BigDecimal.ZERO;
        Map<String, BigDecimal> losses = new TreeMap<>();
        Map<String, BigDecimal> expenses = new TreeMap<>();
        for (Statement branch : branches) {
            net = net.add(branch.netSales());
            cost = cost.add(branch.costOfSales());
            uncosted = uncosted.add(branch.uncostedQuantity());
            branch.losses().forEach(a -> losses.merge(a.code(), a.amount(), BigDecimal::add));
            branch.expenses().forEach(a -> expenses.merge(a.code(), a.amount(), BigDecimal::add));
        }
        headOffice.forEach(a -> expenses.merge(a.code(), a.amount(), BigDecimal::add));
        return statement(null, net, cost, amounts(losses), amounts(expenses), uncosted);
    }

    private static Statement statement(
            UUID branch,
            BigDecimal netSales,
            BigDecimal cost,
            List<Amount> losses,
            List<Amount> expenses,
            BigDecimal uncosted) {
        BigDecimal net = ReportQueries.money(netSales);
        BigDecimal costOfSales = ReportQueries.money(cost);
        BigDecimal gross = net.subtract(costOfSales);
        BigDecimal lossTotal = sum(losses);
        BigDecimal afterLosses = gross.subtract(lossTotal);
        BigDecimal expenseTotal = sum(expenses);
        BigDecimal netProfit = afterLosses.subtract(expenseTotal);
        return new Statement(
                branch,
                net,
                costOfSales,
                gross,
                ReportQueries.percent(gross, net),
                losses,
                lossTotal,
                afterLosses,
                expenses,
                expenseTotal,
                netProfit,
                ReportQueries.percent(netProfit, net),
                uncosted);
    }

    /** Branches that sold, lost or spent anything in the period; just the one when asked. */
    private List<UUID> branchesWithActivity(ReportFilter filter) {
        if (filter.branchId() != null) {
            return List.of(filter.branchId());
        }
        return jdbc.sql(
                        """
                        SELECT branch_id FROM report_sales
                            WHERE business_date BETWEEN :from AND :to
                        UNION
                        SELECT branch_id FROM report_stock_adjustments
                            WHERE business_date BETWEEN :from AND :to
                        UNION
                        SELECT branch_id FROM report_expenses
                            WHERE incurred_on BETWEEN :from AND :to AND branch_id IS NOT NULL
                              AND status = 'APPROVED'
                        ORDER BY 1
                        """)
                .param("from", Date.valueOf(filter.from()))
                .param("to", Date.valueOf(filter.to()))
                .query(UUID.class)
                .list();
    }

    /**
     * Approved expenses by category, for a branch - or head office when {@code branch} is null. Two
     * statements rather than {@code branch_id = :branch OR :branch IS NULL}: PostgreSQL cannot type
     * a null parameter in that position.
     */
    private List<Amount> expensesByCategory(ReportFilter filter, UUID branch) {
        String where = branch == null ? "branch_id IS NULL" : "branch_id = :branch";
        var statement =
                jdbc.sql(
                                """
                                SELECT category, SUM(amount) AS amount FROM report_expenses
                                WHERE status = 'APPROVED' AND %s
                                  AND incurred_on BETWEEN :from AND :to
                                GROUP BY category ORDER BY category
                                """
                                        .formatted(where))
                        .param("from", Date.valueOf(filter.from()))
                        .param("to", Date.valueOf(filter.to()));
        if (branch != null) {
            statement = statement.param("branch", branch);
        }
        return statement
                .query(
                        (rs, row) ->
                                new Amount(
                                        rs.getString("category"),
                                        ReportQueries.money(rs.getBigDecimal("amount"))))
                .list();
    }

    private static List<Amount> amounts(Map<String, BigDecimal> byCode) {
        return byCode.entrySet().stream()
                .map(e -> new Amount(e.getKey(), ReportQueries.money(e.getValue())))
                .toList();
    }

    private static BigDecimal sum(List<Amount> amounts) {
        return ReportQueries.money(
                amounts.stream().map(Amount::amount).reduce(BigDecimal.ZERO, BigDecimal::add));
    }
}
