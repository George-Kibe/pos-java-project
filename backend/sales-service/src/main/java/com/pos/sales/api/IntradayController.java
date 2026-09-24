package com.pos.sales.api;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import org.springframework.data.domain.PageRequest;
import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.sales.api.dto.SalesDtos;
import com.pos.sales.domain.cash.CashCount;
import com.pos.sales.service.IntradayService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The branch's intraday cash, held by the supervisor: tills deposit into it and are replenished
 * from it; money comes in from the bank and goes back to it.
 */
@RestController
@RequestMapping("/api/v1/intraday")
@RequiredArgsConstructor
@Tag(name = "Intraday cash")
public class IntradayController {

    private final IntradayService intraday;
    private final BranchAccessGuard branchAccess;

    public record MovementResponse(
            UUID id,
            String kind,
            UUID tillSessionId,
            BigDecimal denomination,
            int count,
            String reason,
            Instant at,
            UUID by) {}

    public record IntradayResponse(
            UUID branchId,
            List<SalesDtos.CashCountLine> holdings,
            BigDecimal total,
            List<MovementResponse> recent) {}

    public record IntradayRequest(
            @NotNull UUID branchId,
            @NotEmpty @Valid List<SalesDtos.CashLine> notes,
            @Size(max = 500) String reason) {}

    @GetMapping
    @PreAuthorize("hasAuthority('cash:intraday')")
    @Operation(summary = "What the intraday cash holds, note by note, and its latest movements")
    public IntradayResponse get(@RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return view(branchId, intraday.holdings(branchId));
    }

    @PostMapping("/top-ups")
    @PreAuthorize("hasAuthority('cash:intraday')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cash brought into the intraday: from the bank, or an opening balance")
    public IntradayResponse topUp(@Valid @RequestBody IntradayRequest request) {
        branchAccess.requireAccess(request.branchId());
        return view(
                request.branchId(),
                intraday.topUp(request.branchId(), cash(request), request.reason()));
    }

    @PostMapping("/bankings")
    @PreAuthorize("hasAuthority('cash:intraday')")
    @ResponseStatus(HttpStatus.CREATED)
    @Operation(summary = "Cash taken from the intraday to the bank")
    public IntradayResponse bank(@Valid @RequestBody IntradayRequest request) {
        branchAccess.requireAccess(request.branchId());
        return view(
                request.branchId(),
                intraday.bank(request.branchId(), cash(request), request.reason()));
    }

    private static CashCount cash(IntradayRequest request) {
        return CashCount.of(SalesDtos.lines(request.notes()));
    }

    private IntradayResponse view(UUID branchId, CashCount held) {
        return new IntradayResponse(
                branchId,
                SalesDtos.describe(held),
                held.total(),
                intraday.recent(branchId, PageRequest.of(0, 30)).stream()
                        .map(
                                m ->
                                        new MovementResponse(
                                                m.getId(),
                                                m.getKind().name(),
                                                m.getTillSessionId(),
                                                m.getDenomination(),
                                                m.getCount(),
                                                m.getReason(),
                                                m.getOccurredAt(),
                                                m.getCreatedBy()))
                        .toList());
    }
}
