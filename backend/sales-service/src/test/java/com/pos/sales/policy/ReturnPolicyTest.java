package com.pos.sales.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.sales.domain.policy.ReturnEligibility;
import com.pos.sales.domain.policy.ReturnPolicy;

/**
 * What may come back.
 *
 * <p>The clock is a parameter, so "31 days later" is a test rather than a wait.
 */
class ReturnPolicyTest {

    private static final UUID LINE = UUID.randomUUID();
    private static final Instant SOLD = Instant.parse("2026-01-10T10:00:00Z");
    private static final int WINDOW_DAYS = 30;

    private static ReturnEligibility evaluate(
            String sold, String alreadyReturned, String requested, Duration since) {
        return ReturnPolicy.evaluate(
                LINE,
                new BigDecimal(sold),
                new BigDecimal(alreadyReturned),
                new BigDecimal(requested),
                SOLD,
                SOLD.plus(since),
                WINDOW_DAYS);
    }

    @Test
    void aWholeLineComesBackInsideTheWindow() {
        ReturnEligibility eligibility = evaluate("3", "0", "3", Duration.ofDays(2));

        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.requiresOverride()).isFalse();
        assertThat(eligibility.maximumReturnable()).isEqualByComparingTo("3");
    }

    @Test
    @DisplayName("a second partial return is allowed up to what is left")
    void partialReturnsAccumulate() {
        ReturnEligibility first = evaluate("5", "0", "2", Duration.ofDays(1));
        assertThat(first.eligible()).isTrue();

        ReturnEligibility second = evaluate("5", "2", "3", Duration.ofDays(3));
        assertThat(second.eligible()).isTrue();
        assertThat(second.alreadyReturned()).isEqualByComparingTo("2");
        assertThat(second.maximumReturnable()).isEqualByComparingTo("3");

        // One more than remains is refused, with the remaining quantity in the message.
        ReturnEligibility tooMany = evaluate("5", "2", "4", Duration.ofDays(3));
        assertThat(tooMany.eligible()).isFalse();
        assertThat(tooMany.reason()).contains("Only 3");
    }

    @Test
    void aFullyReturnedLineCannotComeBackAgain() {
        ReturnEligibility eligibility = evaluate("2", "2", "1", Duration.ofDays(1));

        assertThat(eligibility.eligible()).isFalse();
        assertThat(eligibility.reason()).contains("already been returned");
    }

    @Test
    @DisplayName("outside the window it is allowed but needs a supervisor")
    void outsideTheWindowNeedsAnOverride() {
        ReturnEligibility eligibility = evaluate("1", "0", "1", Duration.ofDays(31));

        // Not refused: whether to accept a late return is a commercial decision, not arithmetic.
        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.requiresOverride()).isTrue();
        assertThat(eligibility.reason()).contains("supervisor");
    }

    @Test
    @DisplayName("the last day of the window is still inside it")
    void theWindowBoundary() {
        assertThat(evaluate("1", "0", "1", Duration.ofDays(30)).requiresOverride()).isFalse();
        assertThat(evaluate("1", "0", "1", Duration.ofDays(30).plusHours(23)).requiresOverride())
                .isFalse();
        assertThat(evaluate("1", "0", "1", Duration.ofDays(31)).requiresOverride()).isTrue();
    }

    @Test
    void aShopWithNoWindowNeverNeedsAnOverride() {
        ReturnEligibility eligibility =
                ReturnPolicy.evaluate(
                        LINE,
                        BigDecimal.ONE,
                        BigDecimal.ZERO,
                        BigDecimal.ONE,
                        SOLD,
                        SOLD.plus(Duration.ofDays(400)),
                        0);

        assertThat(eligibility.eligible()).isTrue();
        assertThat(eligibility.requiresOverride()).isFalse();
    }

    @Test
    void aZeroOrNegativeQuantityIsRefused() {
        assertThat(evaluate("5", "0", "0", Duration.ofDays(1)).eligible()).isFalse();
        assertThat(evaluate("5", "0", "-1", Duration.ofDays(1)).reason())
                .contains("greater than zero");
    }

    @Test
    @DisplayName("a partial refund is pro-rated, so a promotional discount is honoured back")
    void refundsArePreRated() {
        // Three units charged 200.00 in total after a promotion - 66.67 each, not the 80 shelf
        // price.
        BigDecimal refund =
                ReturnPolicy.refundFor(
                        new BigDecimal("3"), new BigDecimal("200.0000"), new BigDecimal("1"));

        assertThat(refund).isEqualByComparingTo("66.6667");
    }

    @Test
    @DisplayName("a whole-line refund gives back exactly what was charged, with no division")
    void aWholeLineRefundIsExact() {
        BigDecimal refund =
                ReturnPolicy.refundFor(
                        new BigDecimal("3"), new BigDecimal("100.0000"), new BigDecimal("3"));

        // 100/3 pro-rated three times would not come back to 100; the whole-line case avoids it.
        assertThat(refund).isEqualByComparingTo("100.0000");
    }

    @Test
    void refundingNothingFromNothingIsZero() {
        assertThat(
                        ReturnPolicy.refundFor(
                                BigDecimal.ZERO, new BigDecimal("50.0000"), BigDecimal.ZERO))
                .isEqualByComparingTo("0");
    }

    @Test
    void daysAreWholeDaysElapsed() {
        assertThat(ReturnPolicy.daysBetween(SOLD, SOLD.plus(Duration.ofHours(23)))).isZero();
        assertThat(ReturnPolicy.daysBetween(SOLD, SOLD.plus(Duration.ofHours(25)))).isEqualTo(1);
    }
}
