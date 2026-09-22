package com.pos.customer.service;

import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import lombok.RequiredArgsConstructor;

/**
 * The housekeeping points need: lapsing what was never spent, and re-judging tiers.
 *
 * <p>Both have to run on a clock rather than on a member's next visit. Points that lapsed in March
 * must not still be spendable in June because nobody came in, and a tier earned last year must not
 * outlive the spending that earned it.
 */
@Component
@RequiredArgsConstructor
public class LoyaltySweeper {

    private static final int BATCH = 200;

    private final LoyaltyService loyalty;

    @Scheduled(cron = "${pos.loyalty.expiry-cron:0 30 2 * * *}")
    public void expirePoints() {
        while (loyalty.expireLapsed(BATCH) == BATCH) {
            // A backlog after downtime is cleared in batches rather than one long transaction.
        }
    }

    @Scheduled(cron = "${pos.loyalty.tier-cron:0 45 2 * * *}")
    public void reviewTiers() {
        while (loyalty.evaluateStaleTiers(BATCH) == BATCH) {
            // Same: keep each transaction short.
        }
    }
}
