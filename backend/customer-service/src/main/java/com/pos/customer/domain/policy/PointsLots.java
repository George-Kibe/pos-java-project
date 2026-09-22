package com.pos.customer.domain.policy;

import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.UUID;

/**
 * Spending points takes the soonest to expire first.
 *
 * <p>The same idea as FEFO on the shelf, and for the same reason: anything else lets points lapse
 * that a member has already effectively spent, and they notice. Lots with no expiry go last - they
 * can wait.
 *
 * <p>Pure: no Spring, no JPA, no clock.
 */
public final class PointsLots {

    private PointsLots() {}

    /** Points still unspent from one accrual. */
    public record Lot(UUID transactionId, long remaining, Instant expiresAt) {}

    /** How much to take from one lot. */
    public record Take(UUID transactionId, long points) {}

    /**
     * @param shortfall points the lots could not cover; zero when the spend fits
     */
    public record Spend(List<Take> takes, long shortfall) {}

    public static Spend spend(long points, List<Lot> lots) {
        if (points <= 0) {
            throw new IllegalArgumentException("Spending needs a positive number of points");
        }
        List<Lot> ordered =
                lots.stream()
                        .filter(lot -> lot.remaining() > 0)
                        .sorted(
                                Comparator.comparing(
                                                Lot::expiresAt,
                                                Comparator.nullsLast(Comparator.naturalOrder()))
                                        .thenComparing(Lot::transactionId))
                        .toList();

        List<Take> takes = new ArrayList<>();
        long left = points;
        for (Lot lot : ordered) {
            if (left <= 0) {
                break;
            }
            long take = Math.min(left, lot.remaining());
            takes.add(new Take(lot.transactionId(), take));
            left -= take;
        }
        return new Spend(List.copyOf(takes), Math.max(left, 0));
    }
}
