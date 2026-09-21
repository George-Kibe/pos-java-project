package com.pos.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.inventory.domain.MovementType;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.StockTake;
import com.pos.inventory.domain.StockTakeLine;
import com.pos.inventory.repository.StockItemRepository;
import com.pos.inventory.repository.StockTakeRepository;

import lombok.RequiredArgsConstructor;

/**
 * Counting what is actually on the shelf.
 *
 * <p>Snapshot, count, then post. The snapshot is taken when counting begins and kept, so a variance
 * is measured against what the system believed at that moment. Comparing a count against a live
 * figure instead would attribute every sale made during the count to a discrepancy, and the report
 * would be nonsense in a busy shop.
 */
@Service
@RequiredArgsConstructor
public class StockTakeService {

    private final StockTakeRepository stockTakes;
    private final StockItemRepository items;
    private final BatchConsumer batchConsumer;

    public record CountLine(UUID stockItemId, BigDecimal countedQuantity, String notes) {}

    @Transactional(readOnly = true)
    public Page<StockTake> list(UUID branchId, Pageable pageable) {
        return stockTakes.findByBranchIdOrderByCreatedAtDesc(branchId, pageable);
    }

    @Transactional(readOnly = true)
    public StockTake get(UUID id) {
        return stockTakes
                .findWithLinesById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Stock take", id));
    }

    /**
     * Opens a count and snapshots every stock line at the branch.
     *
     * <p>Every line, including those the system believes are empty: a product showing zero that
     * turns out to have six on the shelf is exactly the discrepancy worth finding.
     */
    @Transactional
    public StockTake open(String reference, UUID branchId, String notes) {
        if (stockTakes.existsByReference(reference)) {
            throw new Errors.ConflictException(
                    "stock_take.reference_taken",
                    "A stock take with that reference already exists.");
        }

        StockTake stockTake = new StockTake(reference, branchId);
        stockTake.setNotes(notes);
        stockTake.setSnapshotAt(Instant.now());
        stockTake.setStatus(StockTake.Status.COUNTING);

        List<StockItem> branchItems =
                items.findByBranchId(branchId, Pageable.unpaged()).getContent();
        for (StockItem item : branchItems) {
            stockTake.getLines().add(new StockTakeLine(stockTake, item));
        }

        return stockTakes.save(stockTake);
    }

    /** Records counted quantities. Lines may be counted in any order and re-counted. */
    @Transactional
    public StockTake count(UUID stockTakeId, List<CountLine> counts) {
        StockTake stockTake = get(stockTakeId);
        requireCountable(stockTake);

        UUID actor = AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null);

        for (CountLine count : counts) {
            StockTakeLine line =
                    stockTake.getLines().stream()
                            .filter(
                                    candidate ->
                                            candidate
                                                    .getStockItem()
                                                    .getId()
                                                    .equals(count.stockItemId()))
                            .findFirst()
                            .orElseThrow(
                                    () ->
                                            new Errors.BadRequestException(
                                                    "stock_take.unknown_line",
                                                    "That product is not on this count sheet."));

            if (count.countedQuantity() == null || count.countedQuantity().signum() < 0) {
                throw new Errors.BadRequestException(
                        "stock_take.invalid_count", "A counted quantity cannot be negative.");
            }

            line.setCountedQuantity(count.countedQuantity());
            line.setCountedAt(Instant.now());
            line.setCountedBy(actor);
            line.setNotes(count.notes());
        }

        return stockTakes.save(stockTake);
    }

    /** Moves a counted sheet to review, where the variances can be looked at before posting. */
    @Transactional
    public StockTake submitForReview(UUID stockTakeId) {
        StockTake stockTake = get(stockTakeId);
        requireCountable(stockTake);
        stockTake.setStatus(StockTake.Status.REVIEW);
        return stockTakes.save(stockTake);
    }

    /**
     * Posts the variances as stock movements.
     *
     * <p>Uncounted lines are left alone rather than treated as zero. A line nobody reached is not
     * evidence that the shelf is empty, and writing off stock because a counter ran out of time
     * would be worse than the discrepancy it was trying to fix.
     */
    @Transactional
    public StockTake post(UUID stockTakeId) {
        StockTake stockTake = get(stockTakeId);

        if (stockTake.getStatus() == StockTake.Status.POSTED) {
            throw new Errors.ConflictException(
                    "stock_take.already_posted", "This stock take has already been posted.");
        }
        if (stockTake.getStatus() == StockTake.Status.CANCELLED) {
            throw new Errors.ConflictException(
                    "stock_take.cancelled", "A cancelled stock take cannot be posted.");
        }

        UUID actor = AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null);

        for (StockTakeLine line : stockTake.getLines()) {
            if (!line.hasVariance()) {
                continue;
            }

            StockItem item = line.getStockItem();
            BigDecimal variance = line.variance();

            StockLedgerService.MovementContext context =
                    new StockLedgerService.MovementContext(
                            MovementType.STOCK_TAKE,
                            "COUNT_CORRECTION",
                            "StockTake",
                            stockTake.getId(),
                            actor,
                            Instant.now());

            if (variance.signum() < 0) {
                // Less on the shelf than believed: take it out oldest-dated first, like any other
                // depletion, so the batches that remain are the ones actually there.
                batchConsumer.consume(item, variance.abs(), context);
            } else {
                // More than believed. Given a batch of its own so it can be sold and shows up in
                // the expiry order, rather than floating as an unattributed quantity.
                batchConsumer.addUntrackedStock(
                        item,
                        variance,
                        BatchConsumer.syntheticBatchNumber("COUNT", stockTake.getReference()),
                        null,
                        BigDecimal.ZERO,
                        context);
            }
        }

        stockTake.setStatus(StockTake.Status.POSTED);
        stockTake.setPostedAt(Instant.now());
        stockTake.setPostedBy(actor);
        return stockTakes.save(stockTake);
    }

    @Transactional
    public StockTake cancel(UUID stockTakeId) {
        StockTake stockTake = get(stockTakeId);
        if (stockTake.getStatus() == StockTake.Status.POSTED) {
            throw new Errors.ConflictException(
                    "stock_take.already_posted",
                    "A posted stock take cannot be cancelled. Post an adjustment instead.");
        }
        stockTake.setStatus(StockTake.Status.CANCELLED);
        return stockTakes.save(stockTake);
    }

    private static void requireCountable(StockTake stockTake) {
        if (stockTake.getStatus() != StockTake.Status.COUNTING
                && stockTake.getStatus() != StockTake.Status.REVIEW) {
            throw new Errors.ConflictException(
                    "stock_take.not_counting",
                    "This stock take is %s and is not open for counting."
                            .formatted(stockTake.getStatus()));
        }
    }
}
