package com.pos.inventory.domain.fefo;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

/**
 * A batch with stock left in it, reduced to what allocation needs.
 *
 * <p>Deliberately not the JPA entity, for the same reason as the pricing engine: allocation is
 * where a mistake means selling the wrong carton of milk, and it should be testable by calling it
 * with a list rather than by building a database.
 *
 * @param expiryDate null for stock that does not perish; such a batch waits until dated stock is
 *     gone
 */
public record AvailableBatch(
        UUID batchId,
        String batchNumber,
        LocalDate expiryDate,
        Instant receivedAt,
        BigDecimal quantity,
        BigDecimal unitCost,
        String currency) {}
