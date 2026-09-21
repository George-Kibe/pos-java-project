package com.pos.purchasing.domain.matching;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * One disagreement, in terms a person can act on.
 *
 * @param expected what the order or the receipt says
 * @param actual what the invoice says
 * @param difference {@code actual - expected}; positive means the invoice is the larger figure
 * @param amountEffect what this variance is worth in money, so the findings can be ranked
 */
public record LineVariance(
        UUID productId,
        String sku,
        VarianceType type,
        BigDecimal expected,
        BigDecimal actual,
        BigDecimal difference,
        BigDecimal amountEffect,
        String description) {}
