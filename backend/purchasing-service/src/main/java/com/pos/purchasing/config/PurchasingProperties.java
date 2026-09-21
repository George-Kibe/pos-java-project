package com.pos.purchasing.config;

import java.math.BigDecimal;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.pos.purchasing.domain.matching.MatchTolerance;

/**
 * Tunables that differ between shops.
 *
 * <p>The match tolerance in particular is a commercial decision, not a technical one: how much
 * price drift a business will pay without arguing depends on its margins and its relationship with
 * the supplier.
 *
 * @param defaultCoverDays how many days of stock a reorder should aim to put back
 */
@ConfigurationProperties(prefix = "pos.purchasing")
public record PurchasingProperties(
        int defaultCoverDays,
        BigDecimal matchToleranceAmount,
        BigDecimal matchTolerancePercentage) {

    public MatchTolerance tolerance() {
        return new MatchTolerance(
                matchToleranceAmount == null ? BigDecimal.ZERO : matchToleranceAmount,
                matchTolerancePercentage == null ? BigDecimal.ZERO : matchTolerancePercentage);
    }

    public int coverDaysOrDefault() {
        return defaultCoverDays > 0 ? defaultCoverDays : 14;
    }
}
