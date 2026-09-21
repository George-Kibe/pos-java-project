package com.pos.inventory.domain.fefo;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;

/**
 * Decides which batches a quantity comes out of: first expired, first out.
 *
 * <p>FEFO rather than FIFO. They agree only when deliveries arrive in expiry order, and they often
 * do not - a supplier ships a short-dated pallet after a long-dated one, and FIFO would then sell
 * the fresher stock while the older stock quietly expires on the shelf. Sorting by expiry rather
 * than by arrival is the difference between a discount sticker and a write-off.
 *
 * <p>A batch with no expiry sorts last. Tinned goods can wait; perishables cannot.
 *
 * <p>Pure: no database, no clock, no Spring. The same batches and quantity always produce the same
 * allocation, so two tills selling the same item draw from the same carton.
 */
public final class FefoAllocator {

    private FefoAllocator() {}

    /**
     * Expiry first, then oldest received, then batch number.
     *
     * <p>The batch number is a tie-break that does nothing useful on its own and matters anyway:
     * without it, two batches with identical dates would be ordered by whatever the database
     * returned, and the same sale could draw from different cartons on different tills.
     */
    private static final Comparator<AvailableBatch> FEFO_ORDER =
            Comparator.comparing(
                            AvailableBatch::expiryDate,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            AvailableBatch::receivedAt,
                            Comparator.nullsLast(Comparator.naturalOrder()))
                    .thenComparing(
                            AvailableBatch::batchNumber,
                            Comparator.nullsLast(Comparator.naturalOrder()));

    /**
     * Takes {@code required} from the batches, oldest-dated first.
     *
     * <p>A batch is consumed partially when the line needs less than it holds, and the remainder
     * carries on to the next batch - one sale of three litres can span two deliveries, and both
     * have to be recorded, because each carries its own cost and expiry.
     */
    public static AllocationResult allocate(List<AvailableBatch> batches, BigDecimal required) {
        if (required == null || required.signum() <= 0) {
            throw new IllegalArgumentException(
                    "Required quantity must be positive, was " + required);
        }

        List<AvailableBatch> ordered =
                batches.stream()
                        .filter(batch -> batch.quantity() != null && batch.quantity().signum() > 0)
                        .sorted(FEFO_ORDER)
                        .toList();

        List<Allocation> allocations = new ArrayList<>();
        BigDecimal remaining = required;

        for (AvailableBatch batch : ordered) {
            if (remaining.signum() <= 0) {
                break;
            }
            BigDecimal take = remaining.min(batch.quantity());
            allocations.add(
                    new Allocation(
                            batch.batchId(),
                            batch.batchNumber(),
                            take,
                            batch.unitCost(),
                            batch.currency()));
            remaining = remaining.subtract(take);
        }

        BigDecimal allocated = required.subtract(remaining);
        return new AllocationResult(List.copyOf(allocations), allocated, remaining);
    }

    /** What the batches hold in total, regardless of the cached on-hand figure. */
    public static BigDecimal availableIn(List<AvailableBatch> batches) {
        return batches.stream()
                .map(AvailableBatch::quantity)
                .filter(quantity -> quantity != null && quantity.signum() > 0)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }
}
