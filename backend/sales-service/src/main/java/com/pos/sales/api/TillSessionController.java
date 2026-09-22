package com.pos.sales.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;
import com.pos.sales.api.dto.SalesDtos;
import com.pos.sales.domain.TillSession;
import com.pos.sales.service.TillSessionService;
import com.pos.sales.service.ZReportService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Shifts: open with a float, drop cash, count and close. */
@RestController
@RequestMapping("/api/v1/till-sessions")
@RequiredArgsConstructor
@Tag(name = "Till sessions")
public class TillSessionController {

    private final TillSessionService sessions;
    private final ZReportService zReports;
    private final BranchAccessGuard branchAccess;

    @PostMapping
    @PreAuthorize("hasAuthority('shift:open')")
    @Operation(summary = "Open a shift on a register with a declared float")
    public ResponseEntity<SalesDtos.TillSessionResponse> open(
            @Valid @RequestBody SalesDtos.OpenSessionRequest request) {

        branchAccess.requireAccess(request.branchId());
        TillSession session =
                sessions.open(request.branchId(), request.registerId(), request.openingFloat());
        return ResponseEntity.created(URI.create("/api/v1/till-sessions/" + session.getId()))
                .body(SalesDtos.TillSessionResponse.from(session));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('shift:close:any', 'report:view:branch')")
    @Operation(summary = "Shifts at a branch, most recent first")
    public PageResponse<SalesDtos.TillSessionResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                sessions.list(branchId, pageable), SalesDtos.TillSessionResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('shift:open', 'report:view:branch')")
    @Operation(summary = "One shift, with what the drawer should hold right now")
    public SalesDtos.TillSessionResponse get(@PathVariable UUID id) {
        TillSession session = sessions.require(id);
        branchAccess.requireAccess(session.getBranchId());
        return SalesDtos.TillSessionResponse.from(session);
    }

    @GetMapping("/registers/{registerId}/current")
    @PreAuthorize("hasAuthority('shift:open')")
    @Operation(summary = "The open shift on a register, for a terminal that has just started")
    public SalesDtos.TillSessionResponse current(@PathVariable UUID registerId) {
        TillSession session = sessions.requireOpenForRegister(registerId);
        branchAccess.requireAccess(session.getBranchId());
        return SalesDtos.TillSessionResponse.from(session);
    }

    @PostMapping("/{id}/drops")
    @PreAuthorize("hasAuthority('cash:drop')")
    @Operation(summary = "Move cash from the drawer to the safe")
    public SalesDtos.TillSessionResponse drop(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.CashMovementRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return SalesDtos.TillSessionResponse.from(
                sessions.recordDrop(id, request.amount(), request.reason(), request.reference()));
    }

    @PostMapping("/{id}/float")
    @PreAuthorize("hasAuthority('cash:drop')")
    @Operation(summary = "Top up the drawer with change")
    public SalesDtos.TillSessionResponse addFloat(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.CashMovementRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return SalesDtos.TillSessionResponse.from(
                sessions.addFloat(id, request.amount(), request.reason()));
    }

    @PostMapping("/{id}/begin-close")
    @PreAuthorize("hasAuthority('shift:close')")
    @Operation(summary = "Stop taking sales so the drawer can be counted")
    public SalesDtos.TillSessionResponse beginClose(@PathVariable UUID id) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return SalesDtos.TillSessionResponse.from(sessions.beginClose(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('shift:close')")
    @Operation(summary = "Accept the counted cash and close the shift")
    public SalesDtos.TillSessionResponse close(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.CloseSessionRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return SalesDtos.TillSessionResponse.from(
                sessions.close(id, request.countedCash(), request.notes()));
    }

    @GetMapping("/{id}/z-report")
    @PreAuthorize("hasAnyAuthority('shift:close', 'report:view:branch')")
    @Operation(summary = "What the shift took, by payment method, and whether the drawer agrees")
    public ZReportService.ZReport zReport(@PathVariable UUID id) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return zReports.forSession(id);
    }

    @GetMapping("/{id}/cash-movements")
    @PreAuthorize("hasAnyAuthority('shift:close', 'report:view:branch')")
    @Operation(summary = "Floats, drops and pay-outs on a shift")
    public List<ZReportService.CashMovementSummary> cashMovements(@PathVariable UUID id) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return sessions.movementsOf(id).stream()
                .map(
                        movement ->
                                new ZReportService.CashMovementSummary(
                                        movement.getType().name(),
                                        movement.getAmount(),
                                        movement.getReason(),
                                        movement.getOccurredAt()))
                .toList();
    }
}
