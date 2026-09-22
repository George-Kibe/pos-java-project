package com.pos.payment.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.Test;

import com.pos.payment.domain.policy.RefundAllocation;
import com.pos.payment.domain.policy.RefundAllocation.Refundable;

class RefundAllocationTest {

    private static final UUID A = UUID.randomUUID();
    private static final UUID B = UUID.randomUUID();

    @Test
    void aFullRefundOfOnePaymentIsOneFullShare() {
        RefundAllocation.Plan plan =
                RefundAllocation.allocate(
                        money("232.00"), List.of(new Refundable(A, money("232.00"), money("0"))));

        assertThat(plan.shares())
                .singleElement()
                .satisfies(
                        share -> {
                            assertThat(share.paymentId()).isEqualTo(A);
                            assertThat(share.amount()).isEqualByComparingTo("232.00");
                            assertThat(share.full()).isTrue();
                        });
        assertThat(plan.uncovered()).isEqualByComparingTo("0");
    }

    @Test
    void aPartialRefundIsNotFullSoItCannotBeReversed() {
        RefundAllocation.Plan plan =
                RefundAllocation.allocate(
                        money("116.00"), List.of(new Refundable(A, money("232.00"), money("0"))));

        assertThat(plan.shares().getFirst().full()).isFalse();
    }

    @Test
    void theRestOfAPaymentAlreadyPartRefundedIsNotFullEither() {
        RefundAllocation.Plan plan =
                RefundAllocation.allocate(
                        money("116.00"),
                        List.of(new Refundable(A, money("232.00"), money("116.00"))));

        assertThat(plan.shares().getFirst().amount()).isEqualByComparingTo("116.00");
        assertThat(plan.shares().getFirst().full()).isFalse();
    }

    @Test
    void theLargestPaymentIsDrawnOnFirst() {
        RefundAllocation.Plan plan =
                RefundAllocation.allocate(
                        money("500.00"),
                        List.of(
                                new Refundable(A, money("200.00"), money("0")),
                                new Refundable(B, money("500.00"), money("0"))));

        // One whole, reversible payment rather than two partial ones.
        assertThat(plan.shares())
                .singleElement()
                .satisfies(
                        share -> {
                            assertThat(share.paymentId()).isEqualTo(B);
                            assertThat(share.full()).isTrue();
                        });
    }

    @Test
    void aRefundSpreadsAcrossPaymentsWhenOneIsNotEnough() {
        RefundAllocation.Plan plan =
                RefundAllocation.allocate(
                        money("600.00"),
                        List.of(
                                new Refundable(A, money("200.00"), money("0")),
                                new Refundable(B, money("500.00"), money("0"))));

        assertThat(plan.shares()).hasSize(2);
        assertThat(plan.shares().get(0).amount()).isEqualByComparingTo("500.00");
        assertThat(plan.shares().get(1).amount()).isEqualByComparingTo("100.00");
        assertThat(plan.uncovered()).isEqualByComparingTo("0");
    }

    @Test
    void whatNoPaymentCanCoverIsReportedNotDropped() {
        RefundAllocation.Plan plan =
                RefundAllocation.allocate(
                        money("300.00"), List.of(new Refundable(A, money("232.00"), money("0"))));

        assertThat(plan.uncovered()).isEqualByComparingTo("68.00");
    }

    @Test
    void withNothingPaidThatWayEverythingIsUncovered() {
        RefundAllocation.Plan plan = RefundAllocation.allocate(money("50.00"), List.of());

        assertThat(plan.shares()).isEmpty();
        assertThat(plan.uncovered()).isEqualByComparingTo("50.00");
    }

    @Test
    void aRefundMustBePositive() {
        assertThatThrownBy(() -> RefundAllocation.allocate(money("0"), List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }

    private static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
