package com.pos.reporting.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.sql.Date;
import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Component;

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.catalog.ProductChangedPayload;
import com.pos.events.inventory.BatchExpiringPayload;
import com.pos.events.inventory.StockDeductedPayload;
import com.pos.events.inventory.StockValuedPayload;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.events.sales.SaleVoidedPayload;
import com.pos.events.sales.ShiftClosedPayload;
import com.pos.reporting.domain.policy.BusinessDates;

import lombok.RequiredArgsConstructor;

/**
 * Turns one event into rows.
 *
 * <p>Two properties matter more than anything else here, because a rebuild depends on both:
 *
 * <ul>
 *   <li><b>Each event writes only its own facts</b> and never updates another event's. Kafka orders
 *       events within a topic, not across topics, so whatever arrives first must not decide what
 *       the other writes. A deduction before its sale, a void before its sale: both come out the
 *       same.
 *   <li><b>Every write is idempotent</b> ({@code ON CONFLICT DO NOTHING}, or an upsert guarded by
 *       the event's own time), so replaying the log produces the rows the stream produced.
 * </ul>
 *
 * <p>Always called inside a transaction that already holds the rebuild lock.
 */
@Component
@RequiredArgsConstructor
public class Projector {

    private static final Logger log = LoggerFactory.getLogger(Projector.class);

    private final JdbcClient jdbc;

    /** The topics this projector reads. */
    public static final List<String> TOPICS =
            List.of(
                    Topics.SALES_SALE_COMPLETED,
                    Topics.SALES_SALE_VOIDED,
                    Topics.SALES_RETURN_PROCESSED,
                    Topics.SALES_SHIFT_CLOSED,
                    Topics.INVENTORY_STOCK_DEDUCTED,
                    Topics.INVENTORY_STOCK_VALUED,
                    Topics.INVENTORY_BATCH_EXPIRING,
                    Topics.INVENTORY_ADJUSTMENT_POSTED,
                    Topics.CATALOG_PRODUCT_CHANGED,
                    Topics.PURCHASING_EXPENSE_CHANGED);

    public void apply(String topic, String json) {
        switch (topic) {
            case Topics.SALES_SALE_COMPLETED ->
                    saleCompleted(EventJson.readEnvelope(json, SaleCompletedPayload.class));
            case Topics.SALES_SALE_VOIDED ->
                    saleVoided(EventJson.readEnvelope(json, SaleVoidedPayload.class));
            case Topics.SALES_RETURN_PROCESSED ->
                    returnProcessed(EventJson.readEnvelope(json, ReturnProcessedPayload.class));
            case Topics.SALES_SHIFT_CLOSED ->
                    shiftClosed(EventJson.readEnvelope(json, ShiftClosedPayload.class));
            case Topics.INVENTORY_STOCK_DEDUCTED ->
                    stockDeducted(EventJson.readEnvelope(json, StockDeductedPayload.class));
            case Topics.INVENTORY_STOCK_VALUED ->
                    stockValued(EventJson.readEnvelope(json, StockValuedPayload.class));
            case Topics.INVENTORY_ADJUSTMENT_POSTED ->
                    adjustmentPosted(
                            EventJson.readEnvelope(
                                    json, com.pos.events.inventory.AdjustmentPostedPayload.class));
            case Topics.INVENTORY_BATCH_EXPIRING ->
                    batchExpiring(EventJson.readEnvelope(json, BatchExpiringPayload.class));
            case Topics.CATALOG_PRODUCT_CHANGED ->
                    productChanged(EventJson.readEnvelope(json, ProductChangedPayload.class));
            case Topics.PURCHASING_EXPENSE_CHANGED ->
                    expenseChanged(
                            EventJson.readEnvelope(
                                    json, com.pos.events.purchasing.ExpenseChangedPayload.class));
            default -> log.debug("No projection for {}", topic);
        }
    }

    // --- sales ------------------------------------------------------------------

    /**
     * An expense's latest revision. Replaced only by a higher one, so the row ends the same however
     * the events are ordered or replayed.
     */
    private void expenseChanged(
            EventEnvelope<com.pos.events.purchasing.ExpenseChangedPayload> event) {
        var expense = event.payload();
        jdbc.sql(
                        """
                        INSERT INTO report_expenses
                            (expense_id, expense_number, branch_id, category, description,
                             incurred_on, amount, tax_amount, currency, status, revision)
                        VALUES (:id, :number, :branch, :category, :description, :day, :amount,
                                :tax, :currency, :status, :revision)
                        ON CONFLICT (expense_id) DO UPDATE SET
                            branch_id = EXCLUDED.branch_id,
                            category = EXCLUDED.category,
                            description = EXCLUDED.description,
                            incurred_on = EXCLUDED.incurred_on,
                            amount = EXCLUDED.amount,
                            tax_amount = EXCLUDED.tax_amount,
                            status = EXCLUDED.status,
                            revision = EXCLUDED.revision
                        WHERE report_expenses.revision < EXCLUDED.revision
                        """)
                .param("id", expense.expenseId())
                .param("number", expense.expenseNumber())
                .param("branch", expense.branchId())
                .param("category", expense.category())
                .param("description", expense.description())
                .param("day", Date.valueOf(expense.incurredOn()))
                .param("amount", expense.amount())
                .param("tax", expense.taxAmount() == null ? BigDecimal.ZERO : expense.taxAmount())
                .param("currency", expense.currency() == null ? "KES" : expense.currency())
                .param("status", expense.status())
                .param("revision", expense.revision())
                .update();
    }

    /** Each line of a posted adjustment or count, as it was; shrinkage is summed at read time. */
    private void adjustmentPosted(
            EventEnvelope<com.pos.events.inventory.AdjustmentPostedPayload> event) {
        var adjustment = event.payload();
        Instant at = adjustment.postedAt() != null ? adjustment.postedAt() : event.occurredAt();
        int number = 0;
        for (var line : adjustment.lines()) {
            number++;
            jdbc.sql(
                            """
                            INSERT INTO report_stock_adjustments
                                (adjustment_id, line_number, branch_id, reason_code, posted_at,
                                 business_date, product_id, sku, quantity_delta, value_at_cost,
                                 currency)
                            VALUES (:id, :line, :branch, :reason, :at, :day, :product, :sku,
                                    :quantity, :value, :currency)
                            ON CONFLICT (adjustment_id, line_number) DO NOTHING
                            """)
                    .param("id", adjustment.adjustmentId())
                    .param("line", number)
                    .param("branch", adjustment.branchId())
                    .param("reason", adjustment.reasonCode())
                    .param("at", Timestamp.from(at))
                    .param("day", Date.valueOf(BusinessDates.of(at)))
                    .param("product", line.productId())
                    .param("sku", line.sku())
                    .param("quantity", line.quantityDelta())
                    .param(
                            "value",
                            line.valueAtCost() == null ? BigDecimal.ZERO : line.valueAtCost())
                    .param("currency", line.currency() == null ? "KES" : line.currency())
                    .update();
        }
    }

    private void saleCompleted(EventEnvelope<SaleCompletedPayload> event) {
        SaleCompletedPayload sale = event.payload();
        Instant completedAt = sale.completedAt() != null ? sale.completedAt() : event.occurredAt();
        jdbc.sql(
                        """
                        INSERT INTO report_sales
                            (sale_id, receipt_number, branch_id, register_id, shift_id,
                             cashier_id, customer_id, completed_at, business_date,
                             net_total, tax_total, grand_total, currency)
                        VALUES (:sale, :receipt, :branch, :register, :shift, :cashier,
                                :customer, :at, :day, :net, :tax, :grand, :currency)
                        ON CONFLICT (sale_id) DO NOTHING
                        """)
                .param("sale", sale.saleId())
                .param("receipt", sale.receiptNumber())
                .param("branch", sale.branchId())
                .param("register", sale.registerId())
                .param("shift", sale.shiftId())
                .param("cashier", sale.cashierId())
                .param("customer", sale.customerId())
                .param("at", Timestamp.from(completedAt))
                .param("day", Date.valueOf(BusinessDates.of(completedAt)))
                .param("net", sale.netTotal())
                .param("tax", sale.taxTotal())
                .param("grand", sale.grandTotal())
                .param("currency", sale.currency())
                .update();

        int number = 0;
        for (SaleCompletedPayload.SaleLine line : sale.lines()) {
            number++;
            jdbc.sql(
                            """
                            INSERT INTO report_sale_lines
                                (sale_id, line_number, product_id, sku, product_name, quantity,
                                 line_total, tax_amount, tax_class_code)
                            VALUES (:sale, :n, :product, :sku, :name, :qty, :total, :tax, :class)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("sale", sale.saleId())
                    .param("n", number)
                    .param("product", line.productId())
                    .param("sku", line.sku())
                    .param("name", line.productName())
                    .param("qty", line.quantity())
                    .param("total", line.lineTotal())
                    .param("tax", line.taxAmount() == null ? BigDecimal.ZERO : line.taxAmount())
                    .param("class", line.taxClassCode())
                    .update();
        }

        List<SaleCompletedPayload.Tender> tenders =
                sale.payments() == null ? List.of() : sale.payments();
        int ordinal = 0;
        for (SaleCompletedPayload.Tender tender : tenders) {
            ordinal++;
            jdbc.sql(
                            """
                            INSERT INTO report_sale_tenders (sale_id, ordinal, method, amount)
                            VALUES (:sale, :n, :method, :amount)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("sale", sale.saleId())
                    .param("n", ordinal)
                    .param("method", tender.method().name())
                    .param("amount", tender.amount())
                    .update();
        }
    }

    private void saleVoided(EventEnvelope<SaleVoidedPayload> event) {
        SaleVoidedPayload voided = event.payload();
        jdbc.sql(
                        """
                        INSERT INTO report_sale_voids (sale_id, voided_at, reason_code, approved_by)
                        VALUES (:sale, :at, :reason, :approver)
                        ON CONFLICT (sale_id) DO NOTHING
                        """)
                .param("sale", voided.saleId())
                .param(
                        "at",
                        Timestamp.from(
                                voided.voidedAt() != null ? voided.voidedAt() : event.occurredAt()))
                .param("reason", voided.reasonCode())
                .param("approver", voided.approvedBy())
                .update();
    }

    private void returnProcessed(EventEnvelope<ReturnProcessedPayload> event) {
        ReturnProcessedPayload processed = event.payload();
        Instant at = processed.processedAt() != null ? processed.processedAt() : event.occurredAt();
        jdbc.sql(
                        """
                        INSERT INTO report_returns
                            (return_id, sale_id, branch_id, till_session_id, refund_method,
                             refund_total, currency, processed_at, business_date)
                        VALUES (:return, :sale, :branch, :till, :method, :total, :currency, :at,
                                :day)
                        ON CONFLICT (return_id) DO NOTHING
                        """)
                .param("return", processed.returnId())
                .param("sale", processed.originalSaleId())
                .param("branch", processed.branchId())
                .param("till", processed.tillSessionId())
                // Returns published before the field existed were all cash.
                .param(
                        "method",
                        processed.refundMethod() == null ? "CASH" : processed.refundMethod().name())
                .param("total", processed.refundTotal())
                .param("currency", processed.currency())
                .param("at", Timestamp.from(at))
                .param("day", Date.valueOf(BusinessDates.of(at)))
                .update();

        int ordinal = 0;
        for (ReturnProcessedPayload.ReturnLine line : processed.lines()) {
            ordinal++;
            jdbc.sql(
                            """
                            INSERT INTO report_return_lines
                                (return_id, ordinal, product_id, quantity, resaleable)
                            VALUES (:return, :n, :product, :qty, :resaleable)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("return", processed.returnId())
                    .param("n", ordinal)
                    .param("product", line.productId())
                    .param("qty", line.quantity())
                    .param("resaleable", line.resaleable())
                    .update();
        }
    }

    private void shiftClosed(EventEnvelope<ShiftClosedPayload> event) {
        ShiftClosedPayload shift = event.payload();
        Instant closedAt = shift.closedAt() != null ? shift.closedAt() : event.occurredAt();
        jdbc.sql(
                        """
                        INSERT INTO report_shifts
                            (shift_id, branch_id, register_id, cashier_id, closed_by, opened_at,
                             closed_at, business_date, opening_float, cash_sales, cash_refunds,
                             cash_drops, expected_cash, counted_cash, variance, non_cash_sales,
                             sale_count, currency)
                        VALUES (:shift, :branch, :register, :cashier, :closedBy, :opened, :closed,
                                :day, :float, :cashSales, :cashRefunds, :drops, :expected,
                                :counted, :variance, :nonCash, :count, :currency)
                        ON CONFLICT (shift_id) DO NOTHING
                        """)
                .param("shift", shift.tillSessionId())
                .param("branch", shift.branchId())
                .param("register", shift.registerId())
                .param("cashier", shift.cashierId())
                .param("closedBy", shift.closedBy())
                .param("opened", shift.openedAt() == null ? null : Timestamp.from(shift.openedAt()))
                .param("closed", Timestamp.from(closedAt))
                .param("day", Date.valueOf(BusinessDates.of(closedAt)))
                .param("float", shift.openingFloat())
                .param("cashSales", shift.cashSales())
                .param("cashRefunds", shift.cashRefunds())
                .param("drops", shift.cashDrops())
                .param("expected", shift.expectedCash())
                .param("counted", shift.countedCash())
                .param("variance", shift.variance())
                .param("nonCash", shift.nonCashSales())
                .param("count", shift.saleCount())
                .param("currency", shift.currency())
                .update();
    }

    // --- inventory ----------------------------------------------------------------

    /**
     * The cost of a sale's goods: every batch it drew on, at the landed cost of that batch.
     *
     * <p>Stock sold ahead of its delivery comes out of no batch and has no cost. It is recorded as
     * uncosted - the line quantity less what the batches covered - never as costing nothing.
     */
    private void stockDeducted(EventEnvelope<StockDeductedPayload> event) {
        StockDeductedPayload deducted = event.payload();
        for (StockDeductedPayload.DeductedLine line : deducted.lines()) {
            List<StockDeductedPayload.BatchAllocation> costed =
                    line.allocations() == null
                            ? List.of()
                            : line.allocations().stream()
                                    .filter(a -> a.quantity() != null && a.unitCost() != null)
                                    .toList();
            BigDecimal cost =
                    costed.stream()
                            .map(a -> a.quantity().multiply(a.unitCost()))
                            .reduce(BigDecimal.ZERO, BigDecimal::add);
            BigDecimal costedQuantity =
                    costed.stream()
                            .map(StockDeductedPayload.BatchAllocation::quantity)
                            .reduce(BigDecimal.ZERO, BigDecimal::add)
                            .min(line.quantity());
            jdbc.sql(
                            """
                            INSERT INTO report_sale_costs
                                (sale_id, product_id, quantity, costed_quantity, cost)
                            VALUES (:sale, :product, :qty, :costed, :cost)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("sale", deducted.saleId())
                    .param("product", line.productId())
                    .param("qty", line.quantity())
                    .param("costed", costedQuantity)
                    .param("cost", cost.setScale(4, RoundingMode.HALF_UP))
                    .update();
        }
    }

    private void stockValued(EventEnvelope<StockValuedPayload> event) {
        StockValuedPayload page = event.payload();
        for (StockValuedPayload.ValuedLine line : page.lines()) {
            jdbc.sql(
                            """
                            INSERT INTO report_stock_valuations
                                (snapshot_id, page, page_count, branch_id, valued_at, product_id,
                                 sku, quantity_on_hand, value_at_cost, currency)
                            VALUES (:snapshot, :page, :pages, :branch, :at, :product, :sku, :qty,
                                    :value, :currency)
                            ON CONFLICT DO NOTHING
                            """)
                    .param("snapshot", page.snapshotId())
                    .param("page", page.page())
                    .param("pages", page.pageCount())
                    .param("branch", page.branchId())
                    .param("at", Timestamp.from(page.valuedAt()))
                    .param("product", line.productId())
                    .param("sku", line.sku())
                    .param("qty", line.quantityOnHand())
                    .param("value", line.valueAtCost())
                    .param("currency", line.currency())
                    .update();
        }
    }

    /** The latest report on a batch wins - by when inventory said it, not when it arrived. */
    private void batchExpiring(EventEnvelope<BatchExpiringPayload> event) {
        BatchExpiringPayload batch = event.payload();
        jdbc.sql(
                        """
                        INSERT INTO report_expiring_batches
                            (batch_id, batch_number, product_id, sku, product_name, branch_id,
                             expiry_date, quantity_remaining, value_at_cost, currency, reported_at)
                        VALUES (:batch, :number, :product, :sku, :name, :branch, :expiry, :qty,
                                :value, :currency, :at)
                        ON CONFLICT (batch_id) DO UPDATE SET
                            quantity_remaining = EXCLUDED.quantity_remaining,
                            value_at_cost = EXCLUDED.value_at_cost,
                            expiry_date = EXCLUDED.expiry_date,
                            reported_at = EXCLUDED.reported_at
                        WHERE report_expiring_batches.reported_at <= EXCLUDED.reported_at
                        """)
                .param("batch", batch.batchId())
                .param("number", batch.batchNumber())
                .param("product", batch.productId())
                .param("sku", batch.sku())
                .param("name", batch.productName())
                .param("branch", batch.branchId())
                .param("expiry", Date.valueOf(batch.expiryDate()))
                .param("qty", batch.quantityRemaining())
                .param("value", batch.valueAtCost())
                .param("currency", batch.currency())
                .param("at", Timestamp.from(event.occurredAt()))
                .update();
    }

    // --- catalog ------------------------------------------------------------------

    private void productChanged(EventEnvelope<ProductChangedPayload> event) {
        ProductChangedPayload product = event.payload();
        jdbc.sql(
                        """
                        INSERT INTO report_products
                            (product_id, sku, name, category_id, category_code, active, as_of)
                        VALUES (:product, :sku, :name, :category, :code, :active, :at)
                        ON CONFLICT (product_id) DO UPDATE SET
                            sku = EXCLUDED.sku,
                            name = EXCLUDED.name,
                            category_id = EXCLUDED.category_id,
                            category_code = EXCLUDED.category_code,
                            active = EXCLUDED.active,
                            as_of = EXCLUDED.as_of
                        WHERE report_products.as_of <= EXCLUDED.as_of
                        """)
                .param("product", product.productId())
                .param("sku", product.sku())
                .param("name", product.name())
                .param("category", product.categoryId())
                .param("code", product.categoryCode())
                .param("active", product.active())
                .param("at", Timestamp.from(event.occurredAt()))
                .update();
    }
}
