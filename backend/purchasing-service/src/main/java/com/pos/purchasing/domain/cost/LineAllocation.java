package com.pos.purchasing.domain.cost;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One line's share of a delivery's charges.
 *
 * @param allocatedCharges this line's share, in money
 * @param landedValue goods value plus the allocated share - the authoritative figure
 * @param landedUnitCost {@code landedValue / quantity}, rounded once for storage against the batch
 */
public record LineAllocation(
        UUID lineId,
        BigDecimal allocatedCharges,
        BigDecimal landedValue,
        BigDecimal landedUnitCost) {}
