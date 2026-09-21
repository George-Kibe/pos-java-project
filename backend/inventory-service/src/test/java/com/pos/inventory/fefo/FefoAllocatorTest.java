package com.pos.inventory.fefo;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.pos.inventory.domain.fefo.Allocation;
import com.pos.inventory.domain.fefo.AllocationResult;
import com.pos.inventory.domain.fefo.AvailableBatch;
import com.pos.inventory.domain.fefo.FefoAllocator;

/**
 * Which carton a sale comes out of.
 *
 * <p>Exhaustive because the failure modes are quiet: selling the fresher stock first leaves the
 * older stock to expire, and a partial consumption that loses the remainder puts the books out by
 * an amount nobody notices until a count.
 */
class FefoAllocatorTest {

    private static final Instant MONDAY = Instant.parse("2026-05-04T08:00:00Z");
    private static final Instant THURSDAY = Instant.parse("2026-05-07T08:00:00Z");

    @Nested
    class Ordering {

        @Test
        @DisplayName("the soonest expiry is used first, whatever order the batches arrive in")
        void soonestExpiryFirst() {
            AvailableBatch longDated = batch("LONG", "2026-06-30", THURSDAY, "50");
            AvailableBatch shortDated = batch("SHORT", "2026-05-10", MONDAY, "50");

            AllocationResult result =
                    FefoAllocator.allocate(List.of(longDated, shortDated), new BigDecimal("10"));

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().get(0).batchNumber()).isEqualTo("SHORT");
        }

        @Test
        @DisplayName("FEFO is not FIFO: a short-dated later delivery is sold before an older one")
        void expiryBeatsArrivalOrder() {
            // Received first, but dated further out.
            AvailableBatch older = batch("OLDER-ARRIVAL", "2026-08-01", MONDAY, "50");
            // Received later, but expires sooner.
            AvailableBatch shortDated = batch("SHORT-DATED", "2026-05-12", THURSDAY, "50");

            AllocationResult result =
                    FefoAllocator.allocate(List.of(older, shortDated), new BigDecimal("10"));

            // FIFO would take OLDER-ARRIVAL and leave SHORT-DATED to expire on the shelf.
            assertThat(result.allocations().get(0).batchNumber()).isEqualTo("SHORT-DATED");
        }

        @Test
        @DisplayName("stock with no expiry waits until dated stock is gone")
        void undatedStockSortsLast() {
            AvailableBatch tinned = batch("TINNED", null, MONDAY, "100");
            AvailableBatch fresh = batch("FRESH", "2026-05-10", THURSDAY, "5");

            AllocationResult result =
                    FefoAllocator.allocate(List.of(tinned, fresh), new BigDecimal("8"));

            // Tins can wait; the perishable cannot.
            assertThat(result.allocations()).hasSize(2);
            assertThat(result.allocations().get(0).batchNumber()).isEqualTo("FRESH");
            assertThat(result.allocations().get(1).batchNumber()).isEqualTo("TINNED");
        }

        @Test
        void sameExpiryFallsBackToOldestReceived() {
            AvailableBatch later = batch("LATER", "2026-05-10", THURSDAY, "50");
            AvailableBatch earlier = batch("EARLIER", "2026-05-10", MONDAY, "50");

            AllocationResult result =
                    FefoAllocator.allocate(List.of(later, earlier), new BigDecimal("10"));

            assertThat(result.allocations().get(0).batchNumber()).isEqualTo("EARLIER");
        }

        @Test
        @DisplayName("identical dates still allocate identically on every till")
        void identicalDatesAreBrokenDeterministically() {
            AvailableBatch b = batch("BBB", "2026-05-10", MONDAY, "5");
            AvailableBatch a = batch("AAA", "2026-05-10", MONDAY, "5");

            // Without a tie-break the database's row order would decide, and two tills selling the
            // same item would draw from different cartons.
            assertThat(
                            FefoAllocator.allocate(List.of(b, a), new BigDecimal("3"))
                                    .allocations()
                                    .get(0)
                                    .batchNumber())
                    .isEqualTo("AAA");
            assertThat(
                            FefoAllocator.allocate(List.of(a, b), new BigDecimal("3"))
                                    .allocations()
                                    .get(0)
                                    .batchNumber())
                    .isEqualTo("AAA");
        }
    }

    @Nested
    class PartialConsumption {

        @Test
        @DisplayName("one sale spans two batches, each recorded with its own cost")
        void oneLineSpansTwoBatches() {
            AvailableBatch first = batch("B1", "2026-05-10", MONDAY, "3", "80.00");
            AvailableBatch second = batch("B2", "2026-05-20", THURSDAY, "10", "95.00");

            AllocationResult result =
                    FefoAllocator.allocate(List.of(first, second), new BigDecimal("5"));

            assertThat(result.isComplete()).isTrue();
            assertThat(result.allocated()).isEqualByComparingTo("5");
            assertThat(result.allocations()).hasSize(2);

            Allocation fromFirst = result.allocations().get(0);
            assertThat(fromFirst.batchNumber()).isEqualTo("B1");
            assertThat(fromFirst.quantity()).isEqualByComparingTo("3");
            assertThat(fromFirst.unitCost()).isEqualByComparingTo("80.00");

            Allocation fromSecond = result.allocations().get(1);
            assertThat(fromSecond.batchNumber()).isEqualTo("B2");
            // The remainder, not the whole line again.
            assertThat(fromSecond.quantity()).isEqualByComparingTo("2");
            assertThat(fromSecond.unitCost()).isEqualByComparingTo("95.00");
        }

        @Test
        void spansAsManyBatchesAsItNeeds() {
            AllocationResult result =
                    FefoAllocator.allocate(
                            List.of(
                                    batch("B1", "2026-05-10", MONDAY, "2"),
                                    batch("B2", "2026-05-11", MONDAY, "2"),
                                    batch("B3", "2026-05-12", MONDAY, "2"),
                                    batch("B4", "2026-05-13", MONDAY, "2")),
                            new BigDecimal("7"));

            assertThat(result.allocations()).hasSize(4);
            assertThat(result.allocations().get(3).quantity()).isEqualByComparingTo("1");
            assertThat(sum(result)).isEqualByComparingTo("7");
        }

        @Test
        @DisplayName("taking exactly a batch's contents does not spill into the next one")
        void exactBoundaryUsesOneBatch() {
            AllocationResult result =
                    FefoAllocator.allocate(
                            List.of(
                                    batch("B1", "2026-05-10", MONDAY, "5"),
                                    batch("B2", "2026-05-20", MONDAY, "5")),
                            new BigDecimal("5"));

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().get(0).quantity()).isEqualByComparingTo("5");
        }

        @Test
        @DisplayName("weighed goods allocate fractionally")
        void fractionalQuantities() {
            AllocationResult result =
                    FefoAllocator.allocate(
                            List.of(
                                    batch("B1", "2026-05-10", MONDAY, "0.750"),
                                    batch("B2", "2026-05-20", MONDAY, "2.000")),
                            new BigDecimal("1.235"));

            assertThat(result.allocations()).hasSize(2);
            assertThat(result.allocations().get(0).quantity()).isEqualByComparingTo("0.750");
            assertThat(result.allocations().get(1).quantity()).isEqualByComparingTo("0.485");
            assertThat(sum(result)).isEqualByComparingTo("1.235");
        }
    }

    @Nested
    class Shortfalls {

        @Test
        @DisplayName("not enough stock allocates what exists and reports the rest")
        void reportsAShortfallRatherThanThrowing() {
            AllocationResult result =
                    FefoAllocator.allocate(
                            List.of(batch("B1", "2026-05-10", MONDAY, "2")), new BigDecimal("5"));

            // The customer already walked out with five. Refusing to record it would put the books
            // further from reality, not closer.
            assertThat(result.isShort()).isTrue();
            assertThat(result.allocated()).isEqualByComparingTo("2");
            assertThat(result.shortfall()).isEqualByComparingTo("3");
            assertThat(result.allocations()).hasSize(1);
        }

        @Test
        void noBatchesAtAllIsAFullShortfall() {
            AllocationResult result = FefoAllocator.allocate(List.of(), new BigDecimal("4"));

            assertThat(result.allocations()).isEmpty();
            assertThat(result.allocated()).isEqualByComparingTo("0");
            assertThat(result.shortfall()).isEqualByComparingTo("4");
            assertThat(result.isComplete()).isFalse();
        }

        @Test
        void emptyBatchesAreIgnored() {
            AllocationResult result =
                    FefoAllocator.allocate(
                            List.of(
                                    batch("EMPTY", "2026-05-01", MONDAY, "0"),
                                    batch("HAS-STOCK", "2026-05-10", MONDAY, "5")),
                            new BigDecimal("3"));

            assertThat(result.allocations()).hasSize(1);
            assertThat(result.allocations().get(0).batchNumber()).isEqualTo("HAS-STOCK");
        }
    }

    @Test
    void aNonPositiveQuantityIsRejected() {
        assertThatThrownBy(() -> FefoAllocator.allocate(List.of(), BigDecimal.ZERO))
                .isInstanceOf(IllegalArgumentException.class)
                .hasMessageContaining("must be positive");
        assertThatThrownBy(() -> FefoAllocator.allocate(List.of(), new BigDecimal("-1")))
                .isInstanceOf(IllegalArgumentException.class);
    }

    @Test
    void reportsWhatTheBatchesHold() {
        assertThat(
                        FefoAllocator.availableIn(
                                List.of(
                                        batch("B1", "2026-05-10", MONDAY, "2.5"),
                                        batch("B2", "2026-05-20", MONDAY, "3.5"),
                                        batch("EMPTY", null, MONDAY, "0"))))
                .isEqualByComparingTo("6.0");
    }

    // --- builders -------------------------------------------------------------

    private static BigDecimal sum(AllocationResult result) {
        return result.allocations().stream()
                .map(Allocation::quantity)
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    private static AvailableBatch batch(
            String number, String expiry, Instant receivedAt, String quantity) {
        return batch(number, expiry, receivedAt, quantity, "100.00");
    }

    private static AvailableBatch batch(
            String number, String expiry, Instant receivedAt, String quantity, String unitCost) {
        return new AvailableBatch(
                UUID.randomUUID(),
                number,
                expiry == null ? null : LocalDate.parse(expiry),
                receivedAt,
                new BigDecimal(quantity),
                new BigDecimal(unitCost),
                "KES");
    }
}
