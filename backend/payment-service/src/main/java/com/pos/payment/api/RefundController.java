package com.pos.payment.api;

import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
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
import com.pos.payment.api.dto.PaymentDtos;
import com.pos.payment.domain.Refund;
import com.pos.payment.domain.RefundStatus;
import com.pos.payment.service.RefundService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/refunds")
@RequiredArgsConstructor
@Tag(name = "Refunds")
public class RefundController {

    private final RefundService refunds;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('sale:refund', 'payment:reconcile', 'report:view:branch')")
    @Operation(
            summary =
                    "Refunds at a branch; filter by REQUIRES_ACTION for the ones that need a person")
    public PageResponse<PaymentDtos.RefundResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) RefundStatus status,
            @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                refunds.list(branchId, status, pageable), PaymentDtos.RefundResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('sale:refund', 'payment:reconcile', 'report:view:branch')")
    @Operation(summary = "One refund")
    public PaymentDtos.RefundResponse get(@PathVariable UUID id) {
        return PaymentDtos.RefundResponse.from(scoped(id));
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasAuthority('sale:refund')")
    @Operation(summary = "Key in the reference of a refund run on the card terminal")
    public PaymentDtos.RefundResponse capture(
            @PathVariable UUID id, @Valid @RequestBody PaymentDtos.RefundCaptureRequest request) {
        scoped(id);
        return PaymentDtos.RefundResponse.from(refunds.capture(id, request.terminalReference()));
    }

    @PostMapping("/{id}/settle")
    @PreAuthorize("hasAuthority('sale:refund')")
    @Operation(summary = "Record how a person settled a refund the provider could not; audited")
    public PaymentDtos.RefundResponse settle(
            @PathVariable UUID id, @Valid @RequestBody PaymentDtos.SettleRequest request) {
        scoped(id);
        return PaymentDtos.RefundResponse.from(
                refunds.settleManually(id, request.via(), request.reference(), request.note()));
    }

    private Refund scoped(UUID id) {
        Refund refund = refunds.require(id);
        branchAccess.requireAccess(refund.getBranchId());
        return refund;
    }
}
