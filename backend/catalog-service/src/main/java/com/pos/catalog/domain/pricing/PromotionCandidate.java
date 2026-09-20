package com.pos.catalog.domain.pricing;

import java.math.BigDecimal;
import java.util.UUID;

import com.pos.catalog.domain.PromotionType;

/**
 * A promotion reduced to what pricing actually needs.
 *
 * <p>Deliberately not the JPA entity. Keeping the engine free of persistence is what lets every
 * pricing and tax combination be tested directly, without a database, a Spring context or a fixture
 * - and pricing rules are the part of this system where an untested combination is most likely to
 * be found by a customer rather than by us.
 *
 * @param value a fraction for {@code PERCENTAGE_OFF} (0.10 is 10%), an amount per unit for {@code
 *     AMOUNT_OFF}
 */
public record PromotionCandidate(
        UUID id,
        String code,
        String name,
        PromotionType type,
        BigDecimal value,
        BigDecimal buyQuantity,
        BigDecimal getQuantity,
        BigDecimal minQuantity,
        int priority,
        boolean stackable) {}
