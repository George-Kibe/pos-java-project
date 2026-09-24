package com.pos.sales.domain.cash;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.ArrayList;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.Optional;
import java.util.TreeMap;

/**
 * Change from what the drawer actually holds.
 *
 * <p>Greedy change-making - the biggest note first - is right for an endless supply and wrong for a
 * real drawer: to give 60 from one 50-note and three 20-coins, greedy takes the 50 and is stuck,
 * where three 20s would do. This searches properly (a bounded knapsack over whole shillings) and
 * returns the fewest pieces that make the amount exactly, or nothing if the drawer cannot.
 *
 * <p>Only whole shillings are paid out: a drawer holds no cents, so the sub-shilling part of a
 * change figure is the unpayable remainder the till has always reported.
 */
public final class ChangeMaker {

    /** Past this, finding change is not a till's problem; refuse rather than search. */
    private static final int LIMIT = 200_000;

    private ChangeMaker() {}

    /** The whole shillings of {@code amount} that can be paid in coin, HALF_UP to cents first. */
    public static int payableShillings(BigDecimal amount) {
        return amount.setScale(2, RoundingMode.HALF_UP)
                .setScale(0, RoundingMode.DOWN)
                .intValueExact();
    }

    /** Exactly {@code shillings} from {@code available}, in the fewest pieces - or empty. */
    public static Optional<CashCount> exact(int shillings, CashCount available) {
        if (shillings < 0 || shillings > LIMIT) {
            return Optional.empty();
        }
        List<BigDecimal> denominations = new ArrayList<>(available.counts().keySet());
        int kinds = denominations.size();
        // best[a]: fewest pieces making a from the denominations considered so far.
        // took[d][a]: how many of denomination d that best way to make a uses.
        int[] best = new int[shillings + 1];
        Arrays.fill(best, Integer.MAX_VALUE);
        best[0] = 0;
        int[][] took = new int[kinds][shillings + 1];
        for (int d = 0; d < kinds; d++) {
            int value = denominations.get(d).intValueExact();
            int have = available.counts().get(denominations.get(d));
            int[] next = best.clone();
            for (int amount = value; amount <= shillings; amount++) {
                int most = Math.min(have, amount / value);
                for (int k = 1; k <= most; k++) {
                    int rest = best[amount - k * value];
                    if (rest != Integer.MAX_VALUE && rest + k < next[amount]) {
                        next[amount] = rest + k;
                        took[d][amount] = k;
                    }
                }
            }
            best = next;
        }
        if (best[shillings] == Integer.MAX_VALUE) {
            return Optional.empty();
        }
        TreeMap<BigDecimal, Integer> counts = new TreeMap<>();
        int amount = shillings;
        for (int d = kinds - 1; d >= 0 && amount > 0; d--) {
            int k = took[d][amount];
            if (k > 0) {
                counts.put(denominations.get(d), k);
                amount -= k * denominations.get(d).intValueExact();
            }
        }
        return Optional.of(new CashCount(counts));
    }

    /**
     * {@code amount} in notes and coins as a person would hand it over, largest first, from an
     * endless supply: the breakdown assumed when a till was not told which notes it received.
     */
    public static CashCount asHandedOver(BigDecimal amount) {
        int shillings = payableShillings(amount);
        Map<BigDecimal, Integer> counts = new TreeMap<>();
        for (BigDecimal denomination : Denominations.ALL) {
            int value = denominations(denomination);
            if (shillings >= value) {
                counts.put(denomination, shillings / value);
                shillings %= value;
            }
        }
        return new CashCount(counts);
    }

    private static int denominations(BigDecimal denomination) {
        return denomination.intValueExact();
    }
}
