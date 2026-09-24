package com.pos.sales.api;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotNull;

import org.springframework.http.HttpStatus;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.ResponseStatus;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.sales.domain.cash.CashLimit;
import com.pos.sales.service.CashLimitService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * How much cash a till may hold: a branch default and per-person limits, set by supervisors and
 * managers from the person's record and the organisation's trust in them.
 */
@RestController
@RequestMapping("/api/v1/cash-limits")
@RequiredArgsConstructor
@Tag(name = "Cash limits")
public class CashLimitController {

    private final CashLimitService limits;
    private final BranchAccessGuard branchAccess;

    public record CashLimitResponse(
            UUID id,
            UUID branchId,
            UUID userId,
            BigDecimal limitAmount,
            BigDecimal ceilingAmount,
            String currency) {
        static CashLimitResponse from(CashLimit limit) {
            return new CashLimitResponse(
                    limit.getId(),
                    limit.getBranchId(),
                    limit.getUserId(),
                    limit.getLimitAmount(),
                    limit.getCeilingAmount(),
                    limit.getCurrency());
        }
    }

    /**
     * @param userId absent for the branch's default
     * @param ceilingAmount absent for 120% of the limit
     */
    public record CashLimitRequest(
            @NotNull UUID branchId,
            UUID userId,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 15, fraction = 4)
                    BigDecimal limitAmount,
            @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 15, fraction = 4)
                    BigDecimal ceilingAmount) {}

    @GetMapping
    @PreAuthorize("hasAuthority('till:manage')")
    @Operation(summary = "A branch's cash limits: its default and each person's")
    public List<CashLimitResponse> list(@RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return limits.atBranch(branchId).stream().map(CashLimitResponse::from).toList();
    }

    @PutMapping
    @PreAuthorize("hasAuthority('till:manage')")
    @Operation(summary = "Set the branch default, or one person's limit")
    public CashLimitResponse set(@Valid @RequestBody CashLimitRequest request) {
        branchAccess.requireAccess(request.branchId());
        return CashLimitResponse.from(
                limits.set(
                        request.branchId(),
                        request.userId(),
                        request.limitAmount(),
                        request.ceilingAmount()));
    }

    @DeleteMapping("/{id}")
    @PreAuthorize("hasAuthority('till:manage')")
    @ResponseStatus(HttpStatus.NO_CONTENT)
    @Operation(summary = "Remove a limit; a person then falls back to the branch default")
    public void remove(@PathVariable UUID id) {
        branchAccess.requireAccess(limits.require(id).getBranchId());
        limits.remove(id);
    }
}
