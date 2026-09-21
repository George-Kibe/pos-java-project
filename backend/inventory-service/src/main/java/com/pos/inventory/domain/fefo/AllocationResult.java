package com.pos.inventory.domain.fefo;

import java.math.BigDecimal;
import java.util.List;

/**
 * What allocation could and could not cover.
 *
 * <p>A shortfall is reported rather than thrown. By the time inventory hears about a sale the
 * customer has already left with the goods; refusing to record it would leave the books further
 * from reality, not closer. The shortfall is what drives the negative-stock alert.
 */
public record AllocationResult(
        List<Allocation> allocations, BigDecimal allocated, BigDecimal shortfall) {

    public boolean isComplete() {
        return shortfall.signum() == 0;
    }

    public boolean isShort() {
        return shortfall.signum() > 0;
    }
}
