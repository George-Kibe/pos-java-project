package com.pos.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;
import com.pos.sales.api.dto.SalesDtos;
import com.pos.sales.service.CashPositionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Where the cash is: in each cashier's till and in the branch's intraday cash, and the branch's
 * total. For those who hold the intraday - supervisors and branch managers at their branches, the
 * administrator at every branch.
 */
@RestController
@RequestMapping("/api/v1/cash-positions")
@RequiredArgsConstructor
@Tag(name = "Cash position")
public class CashPositionController {

    /** The drawer and the intraday are counted in shilling notes and coins (Denominations). */
    private static final String CURRENCY = "KES";

    private final CashPositionService positions;
    private final BranchAccessGuard branchAccess;

    public record TillPositionResponse(
            UUID tillSessionId,
            UUID registerId,
            Integer tillNumber,
            String tillLabel,
            UUID cashierId,
            String status,
            Instant openedAt,
            /** What the till holds now: what its shift should hold, nothing once handed over. */
            BigDecimal held,
            String limitState,
            boolean handedOver) {}

    public record BranchPositionResponse(
            UUID branchId,
            String currency,
            List<TillPositionResponse> tills,
            BigDecimal tillsTotal,
            List<SalesDtos.CashCountLine> intraday,
            BigDecimal intradayTotal,
            BigDecimal total) {}

    public record BranchSummaryResponse(
            UUID branchId,
            String currency,
            int openTills,
            BigDecimal tillsTotal,
            BigDecimal intradayTotal,
            BigDecimal total) {}

    @GetMapping
    @PreAuthorize("hasAuthority('cash:intraday')")
    @Operation(
            summary =
                    "Cash per branch - in its tills, in its intraday, in total - for every branch"
                            + " the caller may see")
    public List<BranchSummaryResponse> summaries() {
        return positions.forCaller(AuthenticatedUser.require()).stream()
                .map(
                        position ->
                                new BranchSummaryResponse(
                                        position.branchId(),
                                        CURRENCY,
                                        position.tills().size(),
                                        position.tillsTotal(),
                                        position.intradayTotal(),
                                        position.total()))
                .toList();
    }

    @GetMapping("/{branchId}")
    @PreAuthorize("hasAuthority('cash:intraday')")
    @Operation(summary = "A branch's cash, till by till and note by note in the intraday")
    public BranchPositionResponse branch(@PathVariable UUID branchId) {
        branchAccess.requireAccess(branchId);
        CashPositionService.BranchPosition position = positions.of(branchId);
        return new BranchPositionResponse(
                branchId,
                CURRENCY,
                position.tills().stream()
                        .map(
                                till ->
                                        new TillPositionResponse(
                                                till.session().getId(),
                                                till.session().getRegisterId(),
                                                till.register() == null
                                                        ? null
                                                        : till.register().getNumber(),
                                                till.register() == null
                                                        ? null
                                                        : till.register().label(),
                                                till.session().getCashierId(),
                                                till.session().getStatus().name(),
                                                till.session().getOpenedAt(),
                                                till.held(),
                                                till.state().name(),
                                                till.session().isHandedOver()))
                        .toList(),
                position.tillsTotal(),
                SalesDtos.describe(position.intraday()),
                position.intradayTotal(),
                position.total());
    }
}
