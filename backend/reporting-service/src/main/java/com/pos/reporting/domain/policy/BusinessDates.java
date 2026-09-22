package com.pos.reporting.domain.policy;

import java.time.Instant;
import java.time.LocalDate;
import java.time.ZoneId;

/**
 * The shop's calendar.
 *
 * <p>A sale at 01:30 on Wednesday in Nairobi is 22:30 on Tuesday in UTC. Reports are asked for by
 * the shop's day, so every fact is dated in Nairobi time when it is projected - once, so a rebuild
 * dates it the same way.
 *
 * <p>Pure: no Spring, no JPA.
 */
public final class BusinessDates {

    public static final ZoneId SHOP_ZONE = ZoneId.of("Africa/Nairobi");

    private BusinessDates() {}

    public static LocalDate of(Instant instant) {
        return instant.atZone(SHOP_ZONE).toLocalDate();
    }

    /** The first instant of a business day. */
    public static Instant startOf(LocalDate day) {
        return day.atStartOfDay(SHOP_ZONE).toInstant();
    }
}
