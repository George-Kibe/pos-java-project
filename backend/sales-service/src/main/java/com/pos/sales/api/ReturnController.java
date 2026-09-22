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

import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;
import com.pos.sales.api.dto.SalesDtos;
import com.pos.sales.domain.SaleReturn;
import com.pos.sales.service.CheckoutService;
import com.pos.sales.service.SaleReturnService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Refunds against a sale. */
@RestController
@RequestMapping("/api/v1/returns")
@RequiredArgsConstructor
@Tag(name = "Returns")
public class ReturnController {

    private final SaleReturnService returns;
    private final CheckoutService sales;
    private final BranchAccessGuard branchAccess;

    @GetMapping("/eligibility")
    @PreAuthorize("hasAuthority('sale:refund')")
    @Operation(summary = "What may come back from a sale, before anything is recorded")
    public List<SalesDtos.EligibilityResponse> eligibility(@RequestParam UUID saleId) {
        branchAccess.requireAccess(sales.require(saleId).getBranchId());
        return returns.eligibility(saleId).stream()
                .map(SalesDtos.EligibilityResponse::from)
                .toList();
    }

    @PostMapping
    @PreAuthorize("hasAuthority('sale:refund')")
    @Operation(summary = "Process a refund; outside the window the caller is the approver")
    public ResponseEntity<SalesDtos.ReturnResponse> process(
            @Valid @RequestBody SalesDtos.ReturnRequest request) {

        branchAccess.requireAccess(sales.require(request.saleId()).getBranchId());
        boolean overriding =
                request.policyOverrideReason() != null && !request.policyOverrideReason().isBlank();

        SaleReturn processed =
                returns.process(
                        request.saleId(),
                        request.reason(),
                        request.refundMethod(),
                        request.notes(),
                        overriding ? AuthenticatedUser.require().userId() : null,
                        request.policyOverrideReason(),
                        request.lines().stream()
                                .map(
                                        line ->
                                                new SaleReturnService.ReturnLineRequest(
                                                        line.saleLineId(),
                                                        line.quantity(),
                                                        line.resaleable(),
                                                        line.conditionNote()))
                                .toList());

        return ResponseEntity.created(URI.create("/api/v1/returns/" + processed.getId()))
                .body(SalesDtos.ReturnResponse.from(processed));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('sale:refund', 'report:view:branch')")
    @Operation(summary = "One refund")
    public SalesDtos.ReturnResponse get(@PathVariable UUID id) {
        SaleReturn saleReturn = returns.require(id);
        branchAccess.requireAccess(saleReturn.getBranchId());
        return SalesDtos.ReturnResponse.from(saleReturn);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('sale:refund', 'report:view:branch')")
    @Operation(summary = "Refunds at a branch")
    public PageResponse<SalesDtos.ReturnResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(returns.list(branchId, pageable), SalesDtos.ReturnResponse::from);
    }
}
