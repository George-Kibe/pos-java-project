package com.pos.inventory.service;

import java.time.LocalDate;
import java.util.List;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.inventory.domain.LedgerDiscrepancy;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.messaging.InventoryEventPublisher;
import com.pos.inventory.repository.StockBatchRepository;
import com.pos.inventory.repository.StockItemRepository;

import lombok.RequiredArgsConstructor;

/**
 * The periodic sweeps: what is about to expire, and what the ledger says.
 *
 * <p>Near-expiry has to be found by looking, because nothing happens when a date approaches. Told
 * early enough, a manager can mark stock down or move it to a busier branch; told on the day, the
 * only option left is a write-off.
 */
@Service
@RequiredArgsConstructor
public class InventorySweepService {

    private static final Logger log = LoggerFactory.getLogger(InventorySweepService.class);

    private final StockBatchRepository batches;
    private final StockItemRepository items;
    private final InventoryEventPublisher events;
    private final ReservationService reservations;

    /** How far ahead to look. Long enough to do something about it. */
    @Value("${pos.inventory.expiry-warning-days:14}")
    private int expiryWarningDays;

    @Scheduled(cron = "${pos.inventory.expiry-scan-cron:0 0 6 * * *}")
    @Transactional
    public int scanForExpiringStock() {
        LocalDate today = LocalDate.now();
        LocalDate cutoff = today.plusDays(expiryWarningDays);

        List<StockBatch> expiring = batches.findExpiringOnOrBefore(cutoff);
        for (StockBatch batch : expiring) {
            events.batchExpiring(batch, today);
        }

        if (!expiring.isEmpty()) {
            log.info("{} batch(es) expiring on or before {}", expiring.size(), cutoff);
        }
        return expiring.size();
    }

    @Scheduled(fixedDelayString = "${pos.inventory.reservation-sweep:PT5M}")
    @Transactional
    public int releaseExpiredReservations() {
        return reservations.expireOverdueHolds();
    }

    /**
     * Compares every cached quantity against the sum of its movements.
     *
     * <p>Should always find nothing. Run anyway, because a disagreement means some code path
     * changed stock without writing a movement, and hearing that from a scheduled check is far
     * better than hearing it from a stock take that will not balance.
     */
    @Scheduled(cron = "${pos.inventory.reconciliation-cron:0 30 2 * * *}")
    @Transactional(readOnly = true)
    public List<LedgerDiscrepancy> reconcileLedger() {
        List<LedgerDiscrepancy> discrepancies =
                items.findLedgerDiscrepancies().stream()
                        .map(
                                row ->
                                        new LedgerDiscrepancy(
                                                row.getStockItemId(),
                                                row.getProductId(),
                                                row.getBranchId(),
                                                row.getCachedQuantity(),
                                                row.getLedgerQuantity()))
                        .toList();

        for (LedgerDiscrepancy row : discrepancies) {
            log.error(
                    "Stock item {} (product {} at branch {}) shows {} but its movements sum to {}",
                    row.stockItemId(),
                    row.productId(),
                    row.branchId(),
                    row.cachedQuantity(),
                    row.ledgerQuantity());
        }

        if (discrepancies.isEmpty()) {
            log.debug("Ledger reconciliation clean");
        }
        return discrepancies;
    }
}
