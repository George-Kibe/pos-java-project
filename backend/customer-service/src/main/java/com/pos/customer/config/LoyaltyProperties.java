package com.pos.customer.config;

import java.math.BigDecimal;
import java.time.Duration;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.pos.customer.domain.policy.LoyaltyPolicy;

/**
 * The scheme's economics, as configuration rather than code.
 *
 * <p>Defaults: a point per 100 shillings spent, a point worth one shilling when spent, points
 * lapsing after a year, and a tier judged on the last twelve months. A shop changes any of these
 * without a deployment; changing them does not rewrite what members already earned.
 *
 * @param accrueOnGrandTotal true to earn on what the customer paid, false to earn on the net of
 *     tax. Tax is the government's, not the shop's, so a shop that would rather not pay points on
 *     it sets this false.
 */
@ConfigurationProperties(prefix = "pos.loyalty")
public record LoyaltyProperties(
        BigDecimal spendPerPoint,
        BigDecimal pointValue,
        Integer pointsExpireAfterMonths,
        Integer tierWindowMonths,
        Boolean accrueOnGrandTotal,
        Duration tierEvaluationInterval) {

    public LoyaltyPolicy policy() {
        return new LoyaltyPolicy(
                spendPerPoint == null ? new BigDecimal("100") : spendPerPoint,
                pointValue == null ? BigDecimal.ONE : pointValue);
    }

    public int expiryMonths() {
        return pointsExpireAfterMonths == null ? 12 : pointsExpireAfterMonths;
    }

    public int windowMonths() {
        return tierWindowMonths == null ? 12 : tierWindowMonths;
    }

    public boolean earnsOnGrandTotal() {
        return accrueOnGrandTotal == null || accrueOnGrandTotal;
    }
}
