package com.pos.customer.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.customer.domain.policy.PointsLots;
import com.pos.customer.domain.policy.PointsLots.Lot;

class PointsLotsTest {

    private static final UUID OLD = UUID.fromString("018f3a1c-0000-7000-8000-000000000001");
    private static final UUID NEW = UUID.fromString("018f3a1c-0000-7000-8000-000000000002");
    private static final UUID NEVER = UUID.fromString("018f3a1c-0000-7000-8000-000000000003");

    @Test
    @DisplayName("points about to lapse are spent first")
    void theSoonestToExpireGoesFirst() {
        PointsLots.Spend spend =
                PointsLots.spend(
                        30,
                        List.of(
                                new Lot(NEW, 100, Instant.parse("2027-01-01T00:00:00Z")),
                                new Lot(OLD, 100, Instant.parse("2026-01-01T00:00:00Z"))));

        assertThat(spend.takes()).singleElement().returns(OLD, PointsLots.Take::transactionId);
        assertThat(spend.shortfall()).isZero();
    }

    @Test
    void aSpendCrossesLotsWhenOneIsNotEnough() {
        PointsLots.Spend spend =
                PointsLots.spend(
                        150,
                        List.of(
                                new Lot(OLD, 100, Instant.parse("2026-01-01T00:00:00Z")),
                                new Lot(NEW, 100, Instant.parse("2027-01-01T00:00:00Z"))));

        assertThat(spend.takes()).hasSize(2);
        assertThat(spend.takes().get(0).points()).isEqualTo(100);
        assertThat(spend.takes().get(1).points()).isEqualTo(50);
    }

    @Test
    @DisplayName("points that never expire wait until the dated ones are gone")
    void undatedLotsGoLast() {
        PointsLots.Spend spend =
                PointsLots.spend(
                        10,
                        List.of(
                                new Lot(NEVER, 100, null),
                                new Lot(OLD, 100, Instant.parse("2026-01-01T00:00:00Z"))));

        assertThat(spend.takes()).singleElement().returns(OLD, PointsLots.Take::transactionId);
    }

    @Test
    void whatTheLotsCannotCoverIsReported() {
        PointsLots.Spend spend =
                PointsLots.spend(
                        150, List.of(new Lot(OLD, 20, Instant.parse("2026-01-01T00:00:00Z"))));

        assertThat(spend.takes()).singleElement().returns(20L, PointsLots.Take::points);
        assertThat(spend.shortfall()).isEqualTo(130);
    }

    @Test
    void emptiedLotsAreSkipped() {
        PointsLots.Spend spend =
                PointsLots.spend(
                        10,
                        List.of(
                                new Lot(OLD, 0, Instant.parse("2026-01-01T00:00:00Z")),
                                new Lot(NEW, 50, Instant.parse("2027-01-01T00:00:00Z"))));

        assertThat(spend.takes()).singleElement().returns(NEW, PointsLots.Take::transactionId);
    }

    @Test
    void spendingNothingIsNotASpend() {
        assertThatThrownBy(() -> PointsLots.spend(0, List.of()))
                .isInstanceOf(IllegalArgumentException.class);
    }
}
