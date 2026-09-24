package com.pos.reporting.policy;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.Arrays;
import java.util.List;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.common.error.Errors;
import com.pos.reporting.domain.policy.BusinessDates;
import com.pos.reporting.service.ExportService;
import com.pos.reporting.service.ReportFilter;

class BusinessDatesAndFiltersTest {

    @Test
    @DisplayName("01:30 on Wednesday in Nairobi is Wednesday's business, though UTC says Tuesday")
    void theShopsCalendarNotUtcs() {
        Instant instant = Instant.parse("2026-09-22T22:30:00Z");

        assertThat(BusinessDates.of(instant)).isEqualTo(LocalDate.of(2026, 9, 23));
        assertThat(BusinessDates.startOf(LocalDate.of(2026, 9, 23)))
                .isEqualTo(Instant.parse("2026-09-22T21:00:00Z"));
    }

    @Test
    void aRangeMustRunForwardsAndNotForever() {
        LocalDate day = LocalDate.of(2026, 9, 22);

        assertThatThrownBy(() -> new ReportFilter(day, day.minusDays(1), null, null))
                .isInstanceOf(Errors.BadRequestException.class);
        assertThatThrownBy(() -> new ReportFilter(day, day.plusDays(400), null, null))
                .isInstanceOf(Errors.BadRequestException.class);
        assertThatThrownBy(() -> new ReportFilter(null, day, null, null))
                .isInstanceOf(Errors.BadRequestException.class);
        assertThat(ReportFilter.day(day, null).to()).isEqualTo(day);
    }

    @Test
    @DisplayName("an export shows money to the cent and weighed quantities to the gram")
    void exportFormatting() {
        ExportService exports = new ExportService();
        ExportService.Table table =
                new ExportService.Table(
                        "Sales by product",
                        List.of("From 2026-09-22"),
                        List.of("SKU", "Sold", "Net"),
                        List.of(
                                Arrays.asList(
                                        "BANANA-KG",
                                        new ExportService.Qty(new BigDecimal("1.2345")),
                                        new BigDecimal("160.5377")),
                                Arrays.asList("NO-NAME", null, null)));

        String csv = new String(exports.csv(table), java.nio.charset.StandardCharsets.UTF_8);
        assertThat(csv).startsWith("\uFEFFSKU,Sold,Net").contains("BANANA-KG,1.235,160.54");
        assertThat(csv).contains("NO-NAME,,");

        byte[] pdf = exports.pdf(table);
        assertThat(new String(pdf, 0, 5, java.nio.charset.StandardCharsets.US_ASCII))
                .isEqualTo("%PDF-");
        // Headed by the Realhive mark: the page carries an image.
        assertThat(new String(pdf, java.nio.charset.StandardCharsets.ISO_8859_1))
                .containsPattern("/Subtype\\s*/Image");
    }
}
