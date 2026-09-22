package com.pos.reporting.api;

import java.time.LocalDate;
import java.util.UUID;

import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.reporting.domain.policy.BusinessDates;
import com.pos.reporting.service.DashboardService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/dashboards")
@RequiredArgsConstructor
@Tag(name = "Dashboards")
public class DashboardController {

    private final DashboardService dashboards;
    private final ReportAccess access;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('report:view', 'report:view:branch')")
    @Operation(
            summary =
                    "A branch's day at a glance: revenue, baskets, margin, top movers, dead stock,"
                            + " near-expiry and stock value")
    public DashboardService.Dashboard dashboard(
            @RequestParam UUID branchId,
            @RequestParam(required = false) @DateTimeFormat(iso = DateTimeFormat.ISO.DATE)
                    LocalDate day) {
        access.requireFor(branchId);
        return dashboards.forDay(
                branchId, day == null ? BusinessDates.of(java.time.Instant.now()) : day);
    }
}
