package com.pos.events.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.UUID;

/**
 * A till session closed and counted.
 *
 * <p>{@code variance} is the number a manager actually looks at: counted cash minus what the till
 * says should be there, after the opening float and any drops. Carried on the event so reporting
 * does not have to re-derive it and reach a different answer.
 */
public record ShiftClosedPayload(
        UUID tillSessionId,
        UUID branchId,
        UUID registerId,
        UUID cashierId,
        UUID closedBy,
        Instant openedAt,
        Instant closedAt,
        BigDecimal openingFloat,
        BigDecimal cashSales,
        BigDecimal cashRefunds,
        BigDecimal cashDrops,
        BigDecimal expectedCash,
        BigDecimal countedCash,
        BigDecimal variance,
        BigDecimal nonCashSales,
        int saleCount,
        String currency) {

    /** True when the drawer is short, which is the direction people care about. */
    public boolean isShort() {
        return variance.signum() < 0;
    }
}
