package com.pos.reporting.service;

import java.time.LocalDate;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import com.pos.common.error.Errors;

/**
 * Which business days, which branch, which category.
 *
 * <p>Days are the shop's calendar days, inclusive at both ends. Branch and category are optional; a
 * missing one means "all". The conditions are built from the filters actually present rather than
 * as {@code :x IS NULL OR ...}: PostgreSQL cannot type a null parameter it only ever compares with
 * {@code IS NULL}, and refuses the query.
 */
public record ReportFilter(LocalDate from, LocalDate to, UUID branchId, UUID categoryId) {

    /** Longer than a year is an export job, not a report someone is waiting for. */
    private static final int MAX_DAYS = 366;

    public ReportFilter {
        if (from == null || to == null) {
            throw new Errors.BadRequestException(
                    "report.range_required", "A report needs a from and a to date");
        }
        if (to.isBefore(from)) {
            throw new Errors.BadRequestException(
                    "report.range_backwards", "The range ends before it starts");
        }
        if (java.time.temporal.ChronoUnit.DAYS.between(from, to) >= MAX_DAYS) {
            throw new Errors.BadRequestException(
                    "report.range_too_long", "A report covers at most a year");
        }
    }

    public static ReportFilter day(LocalDate day, UUID branchId) {
        return new ReportFilter(day, day, branchId, null);
    }

    /** SQL conditions and their parameters, for a table aliased as given. */
    Where where(String dateColumn, String branchColumn) {
        Where where = new Where();
        where.add(dateColumn + " BETWEEN :from AND :to", "from", from);
        where.params.put("to", to);
        if (branchId != null) {
            where.add(branchColumn + " = :branch", "branch", branchId);
        }
        return where;
    }

    /** Conditions accumulated for one query. */
    static final class Where {
        final List<String> conditions = new ArrayList<>();
        final Map<String, Object> params = new LinkedHashMap<>();

        Where add(String condition, String name, Object value) {
            conditions.add(condition);
            params.put(name, value);
            return this;
        }

        String sql() {
            return conditions.isEmpty() ? "TRUE" : String.join(" AND ", conditions);
        }
    }
}
