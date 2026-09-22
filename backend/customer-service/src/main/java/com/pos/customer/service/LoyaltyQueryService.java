package com.pos.customer.service;

import java.time.Clock;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.customer.config.LoyaltyProperties;
import com.pos.customer.domain.LoyaltyAccount;
import com.pos.customer.domain.LoyaltyTransaction;
import com.pos.customer.domain.MembershipTier;

import lombok.RequiredArgsConstructor;

/**
 * Reading a member's points for a screen.
 *
 * <p>Separate from {@link LoyaltyService}, which moves points: the balance, its money value and
 * what lapses soon are all things a lane shows and nothing changes.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class LoyaltyQueryService {

    private static final int EXPIRY_WARNING_DAYS = 30;

    private final LoyaltyService loyalty;
    private final LoyaltyProperties properties;
    private final Clock clock;

    /** An account with the things a screen shows alongside it. */
    public record Snapshot(
            LoyaltyAccount account,
            MembershipTier tier,
            java.math.BigDecimal pointsValue,
            long expiringSoon) {}

    public Optional<Snapshot> snapshot(UUID customerId) {
        return loyalty.find(customerId).map(this::toSnapshot);
    }

    public Snapshot requireSnapshot(UUID customerId) {
        return toSnapshot(loyalty.require(customerId));
    }

    public List<LoyaltyTransaction> allTransactions(UUID customerId) {
        return loyalty.allFor(customerId);
    }

    private Snapshot toSnapshot(LoyaltyAccount account) {
        return new Snapshot(
                account,
                loyalty.tierOf(account).orElse(null),
                properties.policy().valueOf(account.getPointsBalance()),
                loyalty.expiringBefore(
                        account.getId(),
                        clock.instant().plus(EXPIRY_WARNING_DAYS, ChronoUnit.DAYS)));
    }
}
