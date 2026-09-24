package com.pos.sales.domain.cash;

import java.math.BigDecimal;
import java.util.List;

import com.pos.common.error.Errors;

/**
 * The notes and coins a Kenyan till holds, largest first. The drawer is tracked in these; anything
 * smaller than a shilling is never in it (see {@link ChangeMaker}).
 */
public final class Denominations {

    private Denominations() {}

    public static final List<BigDecimal> NOTES =
            List.of(
                    new BigDecimal("1000"),
                    new BigDecimal("500"),
                    new BigDecimal("200"),
                    new BigDecimal("100"),
                    new BigDecimal("50"));

    public static final List<BigDecimal> COINS =
            List.of(
                    new BigDecimal("40"),
                    new BigDecimal("20"),
                    new BigDecimal("10"),
                    new BigDecimal("5"),
                    new BigDecimal("1"));

    /** Notes then coins, largest first. */
    public static final List<BigDecimal> ALL =
            java.util.stream.Stream.concat(NOTES.stream(), COINS.stream()).toList();

    public static boolean isNote(BigDecimal value) {
        return NOTES.stream().anyMatch(note -> note.compareTo(value) == 0);
    }

    /** The canonical form of {@code value} (1000, not 1000.0000), or a 400 if it is not one. */
    public static BigDecimal require(BigDecimal value) {
        return ALL.stream()
                .filter(known -> value != null && known.compareTo(value) == 0)
                .findFirst()
                .orElseThrow(
                        () ->
                                new Errors.BadRequestException(
                                        "cash.unknown_denomination",
                                        "%s is not a Kenyan note or coin".formatted(value)));
    }
}
