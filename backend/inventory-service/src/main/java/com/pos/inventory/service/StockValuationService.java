package com.pos.inventory.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.id.UuidV7;
import com.pos.events.inventory.StockValuedPayload;
import com.pos.inventory.messaging.InventoryEventPublisher;

/**
 * What each branch's stock is worth, announced for reporting.
 *
 * <p>Inventory is the only service that costs stock, so it states the value rather than leaving
 * another service to rebuild the ledger from events and disagree with it. Value is each batch's
 * remaining quantity at the landed cost it arrived at; stock that has gone negative carries its
 * quantity and no value, because there is no batch behind it to cost.
 */
@Service
public class StockValuationService {

    private static final Logger log = LoggerFactory.getLogger(StockValuationService.class);

    private final JdbcClient jdbc;
    private final InventoryEventPublisher events;
    private int pageSize;

    public StockValuationService(
            JdbcClient jdbc,
            InventoryEventPublisher events,
            @Value("${pos.inventory.valuation-page-size:500}") int pageSize) {
        this.jdbc = jdbc;
        this.events = events;
        this.pageSize = pageSize;
    }

    /** One snapshot per branch, every night. */
    @Scheduled(cron = "${pos.inventory.valuation-cron:0 15 3 * * *}")
    public void valueEveryBranch() {
        for (UUID branchId : branchesWithStock()) {
            valueBranch(branchId);
        }
    }

    /**
     * Values one branch and announces it, in pages.
     *
     * <p>One transaction for every page, so a snapshot is published whole or not at all: a reader
     * holding pages 1 to 3 of 5 would otherwise report a branch at a fraction of its value.
     *
     * @return the snapshot's id
     */
    @Transactional
    public UUID valueBranch(UUID branchId) {
        Instant valuedAt = Instant.now();
        UUID snapshotId = UuidV7.randomUUID();
        List<StockValuedPayload.ValuedLine> lines = linesFor(branchId);

        int pageCount = Math.max(1, (lines.size() + pageSize - 1) / pageSize);
        for (int page = 0; page < pageCount; page++) {
            List<StockValuedPayload.ValuedLine> slice =
                    lines.subList(page * pageSize, Math.min(lines.size(), (page + 1) * pageSize));
            events.stockValued(
                    new StockValuedPayload(
                            snapshotId,
                            branchId,
                            valuedAt,
                            page + 1,
                            pageCount,
                            new ArrayList<>(slice)));
        }
        log.info(
                "Valued branch {}: {} product(s) in {} page(s)", branchId, lines.size(), pageCount);
        return snapshotId;
    }

    private List<UUID> branchesWithStock() {
        return jdbc.sql("SELECT DISTINCT branch_id FROM stock_items").query(UUID.class).list();
    }

    private List<StockValuedPayload.ValuedLine> linesFor(UUID branchId) {
        return jdbc.sql(
                        """
                        SELECT i.product_id, i.sku, i.quantity_on_hand,
                               COALESCE(SUM(b.quantity * b.unit_cost)
                                        FILTER (WHERE b.quantity > 0), 0) AS value_at_cost,
                               COALESCE(MAX(b.currency), 'KES') AS currency
                        FROM stock_items i
                        LEFT JOIN stock_batches b ON b.stock_item_id = i.id
                        WHERE i.branch_id = :branch AND i.quantity_on_hand <> 0
                        GROUP BY i.id, i.product_id, i.sku, i.quantity_on_hand
                        ORDER BY i.sku, i.product_id
                        """)
                .param("branch", branchId)
                .query(
                        (rs, row) ->
                                new StockValuedPayload.ValuedLine(
                                        rs.getObject("product_id", UUID.class),
                                        rs.getString("sku"),
                                        rs.getBigDecimal("quantity_on_hand"),
                                        rs.getBigDecimal("value_at_cost")
                                                .setScale(4, java.math.RoundingMode.HALF_UP),
                                        rs.getString("currency")))
                .list();
    }

    /** Exposed for a test that wants small pages; configuration sets it otherwise. */
    void pageSize(int size) {
        this.pageSize = size;
    }
}
