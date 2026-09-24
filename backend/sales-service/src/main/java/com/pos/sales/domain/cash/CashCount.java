package com.pos.sales.domain.cash;

import java.math.BigDecimal;
import java.util.Collection;
import java.util.Collections;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;

import com.pos.common.error.Errors;

/**
 * Notes and coins by denomination: what a drawer holds, what a customer handed over, what goes to
 * the intraday cash. Immutable; counts are never negative except in a signed movement.
 */
public record CashCount(Map<BigDecimal, Integer> counts) {

    public CashCount {
        TreeMap<BigDecimal, Integer> ordered = new TreeMap<>(Collections.reverseOrder());
        counts.forEach(
                (denomination, count) -> {
                    if (count != null && count != 0) {
                        ordered.merge(Denominations.require(denomination), count, Integer::sum);
                    }
                });
        ordered.values().removeIf(count -> count == 0);
        counts = Collections.unmodifiableMap(ordered);
    }

    public static final CashCount EMPTY = new CashCount(Map.of());

    /** From request lines; a repeated denomination adds up, a negative count is refused. */
    public static CashCount of(Collection<Line> lines) {
        if (lines == null) {
            return EMPTY;
        }
        TreeMap<BigDecimal, Integer> counts = new TreeMap<>();
        for (Line line : lines) {
            if (line.count() < 0) {
                throw new Errors.BadRequestException(
                        "cash.negative_count", "A count of notes or coins cannot be negative");
            }
            counts.merge(Denominations.require(line.denomination()), line.count(), Integer::sum);
        }
        return new CashCount(counts);
    }

    /** One denomination and how many of it. */
    public record Line(BigDecimal denomination, int count) {}

    public int count(BigDecimal denomination) {
        return counts.getOrDefault(Denominations.require(denomination), 0);
    }

    public BigDecimal total() {
        return counts.entrySet().stream()
                .map(entry -> entry.getKey().multiply(BigDecimal.valueOf(entry.getValue())))
                .reduce(BigDecimal.ZERO, BigDecimal::add);
    }

    public boolean isEmpty() {
        return counts.isEmpty();
    }

    public CashCount plus(CashCount other) {
        TreeMap<BigDecimal, Integer> sum = new TreeMap<>(counts);
        other.counts.forEach((denomination, count) -> sum.merge(denomination, count, Integer::sum));
        return new CashCount(sum);
    }

    public CashCount minus(CashCount other) {
        TreeMap<BigDecimal, Integer> difference = new TreeMap<>(counts);
        other.counts.forEach(
                (denomination, count) -> difference.merge(denomination, -count, Integer::sum));
        return new CashCount(difference);
    }

    /** Whether every note and coin in {@code wanted} is here in at least that number. */
    public boolean covers(CashCount wanted) {
        return wanted.counts.entrySet().stream()
                .allMatch(entry -> counts.getOrDefault(entry.getKey(), 0) >= entry.getValue());
    }

    public List<Line> lines() {
        return counts.entrySet().stream().map(e -> new Line(e.getKey(), e.getValue())).toList();
    }
}
