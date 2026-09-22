package com.pos.customer.policy;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;

import org.junit.jupiter.api.Test;

import com.pos.customer.domain.policy.TierLadder;
import com.pos.customer.domain.policy.TierLadder.Rung;

class TierLadderTest {

    private static final List<Rung> LADDER =
            List.of(
                    new Rung("BRONZE", new BigDecimal("0"), new BigDecimal("1.0")),
                    new Rung("SILVER", new BigDecimal("50000"), new BigDecimal("1.25")),
                    new Rung("GOLD", new BigDecimal("150000"), new BigDecimal("1.5")));

    @Test
    void aSpendReachesTheHighestRungItMeets() {
        assertThat(TierLadder.forSpend(new BigDecimal("0"), LADDER))
                .get()
                .returns("BRONZE", Rung::code);
        assertThat(TierLadder.forSpend(new BigDecimal("49999.99"), LADDER))
                .get()
                .returns("BRONZE", Rung::code);
        assertThat(TierLadder.forSpend(new BigDecimal("50000"), LADDER))
                .get()
                .returns("SILVER", Rung::code);
        assertThat(TierLadder.forSpend(new BigDecimal("1000000"), LADDER))
                .get()
                .returns("GOLD", Rung::code);
    }

    @Test
    void aMemberWhoStopsShoppingComesBackDown() {
        // The same ladder, judged on a window that has since emptied.
        assertThat(TierLadder.forSpend(new BigDecimal("200000"), LADDER))
                .get()
                .returns("GOLD", Rung::code);
        assertThat(TierLadder.forSpend(new BigDecimal("1000"), LADDER))
                .get()
                .returns("BRONZE", Rung::code);
    }

    @Test
    void nullSpendIsNoSpend() {
        assertThat(TierLadder.forSpend(null, LADDER)).get().returns("BRONZE", Rung::code);
    }

    @Test
    void aLadderWithNoGroundFloorPlacesNobody() {
        List<Rung> raised = List.of(new Rung("SILVER", new BigDecimal("50000"), BigDecimal.ONE));

        assertThat(TierLadder.forSpend(new BigDecimal("100"), raised)).isEmpty();
    }
}
