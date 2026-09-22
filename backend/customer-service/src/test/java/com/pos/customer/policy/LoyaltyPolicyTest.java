package com.pos.customer.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;

import org.junit.jupiter.api.Test;
import org.junit.jupiter.params.ParameterizedTest;
import org.junit.jupiter.params.provider.CsvSource;

import com.pos.customer.domain.policy.LoyaltyPolicy;

/** What a shilling earns, and what a point buys. */
class LoyaltyPolicyTest {

    /** A point per 100 shillings; a point worth a shilling. */
    private final LoyaltyPolicy policy = new LoyaltyPolicy(new BigDecimal("100"), BigDecimal.ONE);

    @ParameterizedTest(name = "{0} at x{1} earns {2}")
    @CsvSource({
        "1052.5377, 1.000, 10",
        "99.9999, 1.000, 0",
        "100.0000, 1.000, 1",
        "1000.0000, 1.250, 12",
        "1000.0000, 1.500, 15"
    })
    void pointsAreEarnedWholeAndRoundedDown(String spend, String multiplier, long expected) {
        assertThat(policy.pointsFor(new BigDecimal(spend), new BigDecimal(multiplier)))
                .isEqualTo(expected);
    }

    @Test
    void nothingIsEarnedOnNothing() {
        assertThat(policy.pointsFor(null, BigDecimal.ONE)).isZero();
        assertThat(policy.pointsFor(BigDecimal.ZERO, BigDecimal.ONE)).isZero();
        assertThat(policy.pointsFor(new BigDecimal("-50"), BigDecimal.ONE)).isZero();
    }

    @Test
    void amissingMultiplierIsPlainRate() {
        assertThat(policy.pointsFor(new BigDecimal("500"), null)).isEqualTo(5);
    }

    @Test
    void pointsAreWorthWhatTheySay() {
        assertThat(policy.valueOf(120)).isEqualByComparingTo("120");
    }

    @Test
    void payingWithPointsRoundsUpSoNoPartPointIsInvented() {
        // 120.40 needs 121 points, not 120: the shop covers the difference, not the customer.
        assertThat(policy.pointsToCover(new BigDecimal("120.40"))).isEqualTo(121);
        assertThat(policy.pointsToCover(new BigDecimal("120.00"))).isEqualTo(120);
        assertThat(policy.pointsToCover(BigDecimal.ZERO)).isZero();
    }

    @Test
    void aSchemeNeedsRealNumbers() {
        assertThatThrownBy(() -> new LoyaltyPolicy(BigDecimal.ZERO, BigDecimal.ONE))
                .isInstanceOf(IllegalArgumentException.class);
        assertThatThrownBy(() -> new LoyaltyPolicy(BigDecimal.ONE, BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
