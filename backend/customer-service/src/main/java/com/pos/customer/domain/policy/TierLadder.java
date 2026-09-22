package com.pos.customer.domain.policy;

import java.math.BigDecimal;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;

/**
 * Which tier a rolling spend earns.
 *
 * <p>Both directions: spend falls out of the window as it ages, so a member who stops shopping
 * comes back down. A tier that only ever went up would say nothing about a customer today.
 *
 * <p>Pure: no Spring, no JPA, no clock.
 */
public final class TierLadder {

    private TierLadder() {}

    /** A rung: its code and what it takes to stand on it. */
    public record Rung(String code, BigDecimal minimumRollingSpend, BigDecimal pointsMultiplier) {}

    /**
     * The highest rung the spend reaches.
     *
     * @return empty when no tier has a threshold this spend meets - a ladder with no ground floor
     */
    public static Optional<Rung> forSpend(BigDecimal rollingSpend, List<Rung> rungs) {
        BigDecimal spend = rollingSpend == null ? BigDecimal.ZERO : rollingSpend;
        return rungs.stream()
                .filter(rung -> rung.minimumRollingSpend().compareTo(spend) <= 0)
                .max(Comparator.comparing(Rung::minimumRollingSpend));
    }
}
