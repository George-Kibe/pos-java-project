package com.pos.purchasing.cost;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.ArrayList;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.pos.purchasing.domain.cost.AllocationBasis;
import com.pos.purchasing.domain.cost.ChargeableLine;
import com.pos.purchasing.domain.cost.LandedCostAllocator;
import com.pos.purchasing.domain.cost.LineAllocation;

/**
 * Freight and duty spread across a delivery.
 *
 * <p>The invariant worth most here is that the parts sum to the whole. An allocation that loses a
 * fraction of a cent per line leaves a residual nobody can explain, and it does so on almost every
 * real delivery rather than in some rare case.
 */
class LandedCostAllocatorTest {

    private static ChargeableLine line(String quantity, String value) {
        return new ChargeableLine(
                UUID.randomUUID(), new BigDecimal(quantity), new BigDecimal(value));
    }

    private static BigDecimal totalAllocated(List<LineAllocation> allocations) {
        return allocations.stream()
                .map(LineAllocation::allocatedCharges)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    @Nested
    class ByValue {

        @Test
        @DisplayName("a line worth twice as much carries twice the freight")
        void allocatesInProportionToValue() {
            List<ChargeableLine> lines = List.of(line("10", "1000.00"), line("10", "2000.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("300.00"), AllocationBasis.BY_VALUE);

            assertThat(allocations.get(0).allocatedCharges()).isEqualByComparingTo("100.00");
            assertThat(allocations.get(1).allocatedCharges()).isEqualByComparingTo("200.00");
        }

        @Test
        void addsTheShareToTheLineValueAndDerivesAUnitCost() {
            List<ChargeableLine> lines = List.of(line("10", "1000.00"), line("10", "2000.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("300.00"), AllocationBasis.BY_VALUE);

            assertThat(allocations.get(0).landedValue()).isEqualByComparingTo("1100.00");
            // 1100 over 10 units: the freight put 10.00 on every unit.
            assertThat(allocations.get(0).landedUnitCost()).isEqualByComparingTo("110.00");
            assertThat(allocations.get(1).landedUnitCost()).isEqualByComparingTo("220.00");
        }

        @Test
        @DisplayName("a delivery of free samples falls back to quantity rather than refusing")
        void fallsBackToQuantityWhenThereIsNoValue() {
            List<ChargeableLine> lines = List.of(line("10", "0.00"), line("30", "0.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("400.00"), AllocationBasis.BY_VALUE);

            assertThat(allocations.get(0).allocatedCharges()).isEqualByComparingTo("100.00");
            assertThat(allocations.get(1).allocatedCharges()).isEqualByComparingTo("300.00");
            assertThat(totalAllocated(allocations)).isEqualByComparingTo("400.00");
        }
    }

    @Nested
    class ByQuantity {

        @Test
        void allocatesPerUnitRegardlessOfValue() {
            List<ChargeableLine> lines = List.of(line("10", "50000.00"), line("30", "100.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("400.00"), AllocationBasis.BY_QUANTITY);

            // 40 units, 10.00 of freight each - the whisky carries no more than the flour.
            assertThat(allocations.get(0).allocatedCharges()).isEqualByComparingTo("100.00");
            assertThat(allocations.get(1).allocatedCharges()).isEqualByComparingTo("300.00");
        }

        @Test
        @DisplayName("value and quantity bases give genuinely different answers")
        void theBasisChangesTheOutcome() {
            List<ChargeableLine> lines = List.of(line("10", "50000.00"), line("30", "100.00"));
            BigDecimal charges = new BigDecimal("400.00");

            BigDecimal byValue =
                    LandedCostAllocator.allocate(lines, charges, AllocationBasis.BY_VALUE)
                            .get(0)
                            .allocatedCharges();
            BigDecimal byQuantity =
                    LandedCostAllocator.allocate(lines, charges, AllocationBasis.BY_QUANTITY)
                            .get(0)
                            .allocatedCharges();

            assertThat(byValue).isNotEqualByComparingTo(byQuantity);
            // By value the expensive line takes almost all of it.
            assertThat(byValue).isGreaterThan(new BigDecimal("399.00"));
            assertThat(byQuantity).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    class NothingIsLost {

        @Test
        @DisplayName("three equal lines splitting 100 still sum to exactly 100")
        void theClassicThirdsCase() {
            List<ChargeableLine> lines =
                    List.of(line("1", "100.00"), line("1", "100.00"), line("1", "100.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("100.00"), AllocationBasis.BY_VALUE);

            assertThat(totalAllocated(allocations)).isEqualByComparingTo("100.00");
            // Two lines get the rounded third; the first carries the remainder, because the
            // lines are all the same size and the tie breaks towards the earliest.
            assertThat(allocations)
                    .extracting(LineAllocation::allocatedCharges)
                    .containsExactly(
                            new BigDecimal("33.3334"),
                            new BigDecimal("33.3333"),
                            new BigDecimal("33.3333"));
        }

        @Test
        void sumsExactlyAcrossAwkwardSplits() {
            for (int count = 1; count <= 13; count++) {
                List<ChargeableLine> lines = new ArrayList<>();
                for (int i = 0; i < count; i++) {
                    lines.add(line("7", "99.99"));
                }
                BigDecimal charges = new BigDecimal("1000.01");

                List<LineAllocation> allocations =
                        LandedCostAllocator.allocate(lines, charges, AllocationBasis.BY_VALUE);

                assertThat(totalAllocated(allocations))
                        .as("%d lines splitting %s", count, charges)
                        .isEqualByComparingTo(charges);
            }
        }

        @Test
        @DisplayName("the remainder always lands on the largest line, and always the same one")
        void theRemainderIsDeterministic() {
            List<ChargeableLine> lines =
                    List.of(line("1", "10.00"), line("1", "1000.00"), line("1", "10.00"));

            List<LineAllocation> first =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("100.00"), AllocationBasis.BY_VALUE);
            List<LineAllocation> second =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("100.00"), AllocationBasis.BY_VALUE);

            assertThat(first).isEqualTo(second);
            assertThat(LandedCostAllocator.largestLineIndex(lines, AllocationBasis.BY_VALUE))
                    .isEqualTo(1);
            assertThat(totalAllocated(first)).isEqualByComparingTo("100.00");
        }
    }

    @Nested
    class EdgeCases {

        @Test
        void oneLineTakesEverything() {
            List<ChargeableLine> lines = List.of(line("5", "500.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("123.45"), AllocationBasis.BY_VALUE);

            assertThat(allocations.getFirst().allocatedCharges()).isEqualByComparingTo("123.45");
            assertThat(allocations.getFirst().landedValue()).isEqualByComparingTo("623.45");
        }

        @Test
        @DisplayName("no charges means the landed cost is simply the goods cost")
        void zeroChargesAllocateNothing() {
            List<ChargeableLine> lines = List.of(line("10", "1000.00"), line("10", "2000.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(lines, BigDecimal.ZERO, AllocationBasis.BY_VALUE);

            assertThat(totalAllocated(allocations)).isEqualByComparingTo("0");
            assertThat(allocations.get(0).landedUnitCost()).isEqualByComparingTo("100.00");
        }

        @Test
        void aFractionalQuantityGetsAFractionalUnitCost() {
            // Weighed goods: 12.500 kg of cheese with freight on top.
            List<ChargeableLine> lines = List.of(line("12.500", "5000.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(
                            lines, new BigDecimal("250.00"), AllocationBasis.BY_VALUE);

            assertThat(allocations.getFirst().landedValue()).isEqualByComparingTo("5250.00");
            assertThat(allocations.getFirst().landedUnitCost()).isEqualByComparingTo("420.00");
        }

        @Test
        void refusesWhatItCannotAllocate() {
            List<ChargeableLine> lines = List.of(line("1", "10.00"));

            assertThatThrownBy(
                            () ->
                                    LandedCostAllocator.allocate(
                                            List.of(), BigDecimal.TEN, AllocationBasis.BY_VALUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("no lines");

            assertThatThrownBy(
                            () ->
                                    LandedCostAllocator.allocate(
                                            lines,
                                            new BigDecimal("-1.00"),
                                            AllocationBasis.BY_VALUE))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("negative");
        }

        @Test
        @DisplayName("a missing basis behaves as by-value rather than throwing")
        void aNullBasisDefaults() {
            List<ChargeableLine> lines = List.of(line("10", "1000.00"), line("10", "3000.00"));

            List<LineAllocation> allocations =
                    LandedCostAllocator.allocate(lines, new BigDecimal("400.00"), null);

            assertThat(allocations.get(0).allocatedCharges()).isEqualByComparingTo("100.00");
            assertThat(allocations.get(1).allocatedCharges()).isEqualByComparingTo("300.00");
        }
    }
}
