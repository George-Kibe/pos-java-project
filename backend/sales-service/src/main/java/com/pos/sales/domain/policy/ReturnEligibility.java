package com.pos.sales.domain.policy;

import java.math.BigDecimal;
import java.util.UUID;

/**
 * Whether a line may be returned, and how much of it.
 *
 * @param alreadyReturned how much of the original line has come back before, which is what makes a
 *     second partial return safe
 * @param requiresOverride true when the sale is outside the policy window, so a supervisor has to
 *     take responsibility
 */
public record ReturnEligibility(
        UUID saleLineId,
        boolean eligible,
        BigDecimal maximumReturnable,
        BigDecimal alreadyReturned,
        boolean requiresOverride,
        String reason) {

    public static ReturnEligibility allowed(
            UUID saleLineId,
            BigDecimal maximumReturnable,
            BigDecimal alreadyReturned,
            boolean requiresOverride) {
        return new ReturnEligibility(
                saleLineId,
                true,
                maximumReturnable,
                alreadyReturned,
                requiresOverride,
                requiresOverride ? "Outside the returns window; needs a supervisor" : null);
    }

    public static ReturnEligibility refused(
            UUID saleLineId, BigDecimal alreadyReturned, String reason) {
        return new ReturnEligibility(
                saleLineId, false, BigDecimal.ZERO, alreadyReturned, false, reason);
    }
}
