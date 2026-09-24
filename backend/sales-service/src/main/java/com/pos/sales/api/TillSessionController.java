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
    private final com.pos.sales.service.RegisterService registers;
    private final com.pos.sales.service.CashDrawerService drawer;
    private final com.pos.sales.service.CashLimitService limits;

    /** A shift as the lane shows it: with its till's number. */
    private SalesDtos.TillSessionResponse respond(TillSession session) {
        return SalesDtos.TillSessionResponse.from(
                session,
                registers.byIds(List.of(session.getRegisterId())).get(session.getRegisterId()));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('shift:open')")
    @Operation(summary = "Open a shift on a register with a declared float")
    public ResponseEntity<SalesDtos.TillSessionResponse> open(
            @Valid @RequestBody SalesDtos.OpenSessionRequest request) {

        branchAccess.requireAccess(request.branchId());
        TillSession session =
                sessions.open(
                        request.branchId(),
                        request.registerId(),
                        request.openingFloat(),
                        SalesDtos.lines(request.floatCount()));
        return ResponseEntity.created(URI.create("/api/v1/till-sessions/" + session.getId()))
                .body(respond(session));
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('shift:close:any', 'report:view:branch')")
    @Operation(summary = "Shifts at a branch, most recent first")
    public PageResponse<SalesDtos.TillSessionResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        var page = sessions.list(branchId, pageable);
        var tills =
                registers.byIds(
                        page.getContent().stream().map(TillSession::getRegisterId).toList());
        return PageResponse.of(
                page,
                session ->
                        SalesDtos.TillSessionResponse.from(
                                session, tills.get(session.getRegisterId())));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('shift:open', 'report:view:branch')")
    @Operation(summary = "One shift, with what the drawer should hold right now")
    public SalesDtos.TillSessionResponse get(@PathVariable UUID id) {
        TillSession session = sessions.require(id);
        branchAccess.requireAccess(session.getBranchId());
        return respond(session);
    }

    @GetMapping("/registers/{registerId}/current")
    @PreAuthorize("hasAuthority('shift:open')")
    @Operation(summary = "The open shift on a register, for a terminal that has just started")
    public SalesDtos.TillSessionResponse current(@PathVariable UUID registerId) {
        TillSession session = sessions.requireOpenForRegister(registerId);
        branchAccess.requireAccess(session.getBranchId());
        return respond(session);
    }

    @PostMapping("/{id}/drops")
    @PreAuthorize("hasAuthority('cash:drop')")
    @Operation(summary = "Move cash from the drawer to the safe")
    public SalesDtos.TillSessionResponse drop(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.CashMovementRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return respond(
                sessions.recordDrop(
                        id,
                        request.amount(),
                        request.reason(),
                        request.reference(),
                        SalesDtos.lines(request.notes())));
    }

    @PostMapping("/{id}/float")
    @PreAuthorize("hasAuthority('cash:drop')")
    @Operation(summary = "Top up the drawer with change")
    public SalesDtos.TillSessionResponse addFloat(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.CashMovementRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return respond(
                sessions.addFloat(
                        id, request.amount(), request.reason(), SalesDtos.lines(request.notes())));
    }

    @PostMapping("/{id}/replenishments")
    @PreAuthorize("hasAuthority('cash:intraday')")
    @Operation(
            summary =
                    "Change for a till from the branch's intraday cash; approved by whoever holds it")
    public SalesDtos.TillSessionResponse replenish(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.ReplenishRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return respond(sessions.replenish(id, SalesDtos.lines(request.notes()), request.reason()));
    }

    @PostMapping("/{id}/exchanges")
    @PreAuthorize("hasAuthority('shift:open')")
    @Operation(
            summary =
                    "Exchange notes for notes of the same total at the till - breaking a 1000 -"
                            + " without changing what it holds in money")
    public SalesDtos.TillSessionResponse exchange(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.ExchangeRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return respond(
                sessions.exchange(
                        id, SalesDtos.lines(request.received()), SalesDtos.lines(request.given())));
    }

    @GetMapping("/{id}/drawer")
    @PreAuthorize("hasAnyAuthority('shift:open', 'shift:close:any', 'report:view:branch')")
    @Operation(
            summary =
                    "What the drawer holds note by note, and where it stands against its cash"
                            + " limit")
    public SalesDtos.DrawerResponse drawer(@PathVariable UUID id) {
        TillSession session = sessions.require(id);
        branchAccess.requireAccess(session.getBranchId());
        var held = session.isTracksDenominations() ? drawer.holdings(id) : null;
        var standing = limits.standing(session);
        var expected = session.reconcile(null).expectedCash();
        return new SalesDtos.DrawerResponse(
                id,
                session.isTracksDenominations(),
                held == null ? List.of() : SalesDtos.describe(held),
                held == null ? null : held.total(),
                expected,
                held == null ? null : expected.subtract(held.total()),
                standing.state().name(),
                standing.limit(),
                standing.ceiling(),
                drawer.closingCount(id).stream()
                        .map(
                                line ->
                                        new SalesDtos.ClosingCountLine(
                                                line.getDenomination(),
                                                line.getExpected(),
                                                line.getCounted(),
                                                line.getCounted() - line.getExpected()))
                        .toList());
    }

    @PostMapping("/{id}/begin-close")
    @PreAuthorize("hasAuthority('shift:close')")
    @Operation(summary = "Stop taking sales so the drawer can be counted")
    public SalesDtos.TillSessionResponse beginClose(@PathVariable UUID id) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return respond(sessions.beginClose(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('shift:close')")
    @Operation(summary = "Accept the counted cash and close the shift")
    public SalesDtos.TillSessionResponse close(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.CloseSessionRequest request) {
        branchAccess.requireAccess(sessions.require(id).getBranchId());
        return respond(
                sessions.close(
                        id,
                        request.countedCash(),
                        request.notes(),
                        SalesDtos.lines(request.countedNotes())));
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
