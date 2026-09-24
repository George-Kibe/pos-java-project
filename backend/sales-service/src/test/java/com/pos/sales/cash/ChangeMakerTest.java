package com.pos.sales.cash;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.Map;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.common.error.Errors;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.domain.cash.ChangeMaker;

@DisplayName("Change from what the drawer holds")
class ChangeMakerTest {

    private static CashCount drawer(Object... pairs) {
        java.util.Map<BigDecimal, Integer> counts = new java.util.HashMap<>();
        for (int i = 0; i < pairs.length; i += 2) {
            counts.put(new BigDecimal(pairs[i].toString()), (Integer) pairs[i + 1]);
        }
        return new CashCount(counts);
    }

    @Test
    void findsChangeGreedyWouldMiss() {
        // 60 from one 50 and three 20s: greedy takes the 50 and is stuck at 10.
        CashCount change = ChangeMaker.exact(60, drawer(50, 1, 20, 3)).orElseThrow();
        assertThat(change).isEqualTo(drawer(20, 3));
    }

    @Test
    void usesTheFewestPieces() {
        CashCount change = ChangeMaker.exact(80, drawer(50, 2, 40, 2, 20, 5, 10, 5)).orElseThrow();
        assertThat(change).isEqualTo(drawer(40, 2));
        assertThat(change.total()).isEqualByComparingTo("80");
    }

    @Test
    void neverGivesWhatTheDrawerDoesNotHold() {
        assertThat(ChangeMaker.exact(35, drawer(20, 1, 10, 1))).isEmpty();
        assertThat(ChangeMaker.exact(35, drawer(20, 1, 10, 1, 5, 1)))
                .contains(drawer(20, 1, 10, 1, 5, 1));
        assertThat(ChangeMaker.exact(0, CashCount.EMPTY)).contains(CashCount.EMPTY);
    }

    @Test
    void paysWholeShillingsAndLeavesTheCentsAsBefore() {
        assertThat(ChangeMaker.payableShillings(new BigDecimal("81.80"))).isEqualTo(81);
        assertThat(ChangeMaker.payableShillings(new BigDecimal("81.995"))).isEqualTo(82);
        assertThat(ChangeMaker.payableShillings(new BigDecimal("0.4"))).isZero();
    }

    @Test
    void breaksATenderIntoTheNotesPeopleHandOver() {
        assertThat(ChangeMaker.asHandedOver(new BigDecimal("1750")))
                .isEqualTo(drawer(1000, 1, 500, 1, 200, 1, 50, 1));
    }

    @Test
    void countsAddSubtractAndCover() {
        CashCount held = drawer(1000, 2, 50, 3);
        CashCount out = drawer(50, 1);
        assertThat(held.minus(out)).isEqualTo(drawer(1000, 2, 50, 2));
        assertThat(held.plus(out).total()).isEqualByComparingTo("2200");
        assertThat(held.covers(drawer(50, 3))).isTrue();
        assertThat(held.covers(drawer(50, 4))).isFalse();
        assertThat(held.covers(drawer(20, 1))).isFalse();
    }

    @Test
    void refusesWhatIsNotKenyanCash() {
        assertThatThrownBy(() -> new CashCount(Map.of(new BigDecimal("25"), 1)))
                .isInstanceOf(Errors.BadRequestException.class);
        assertThatThrownBy(
                        () ->
                                CashCount.of(
                                        java.util.List.of(
                                                new CashCount.Line(new BigDecimal("50"), -1))))
                .isInstanceOf(Errors.BadRequestException.class);
    }
}
