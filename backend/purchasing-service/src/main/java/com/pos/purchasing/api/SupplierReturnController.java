package com.pos.purchasing.api;

import java.net.URI;
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
import com.pos.purchasing.api.dto.PurchasingDtos;
import com.pos.purchasing.domain.SupplierReturn;
import com.pos.purchasing.service.SupplierReturnService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Goods going back to the supplier. */
@RestController
@RequestMapping("/api/v1/supplier-returns")
@RequiredArgsConstructor
@Tag(name = "Supplier returns")
public class SupplierReturnController {

    private final SupplierReturnService returns;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "Returns raised at a branch")
    public PageResponse<PurchasingDtos.SupplierReturnResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {

        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                returns.list(branchId, pageable), PurchasingDtos.SupplierReturnResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "One return")
    public PurchasingDtos.SupplierReturnResponse get(@PathVariable UUID id) {
        SupplierReturn supplierReturn = returns.require(id);
        branchAccess.requireAccess(supplierReturn.getBranchId());
        return PurchasingDtos.SupplierReturnResponse.from(supplierReturn);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:receive')")
    @Operation(summary = "Raise a return")
    public ResponseEntity<PurchasingDtos.SupplierReturnResponse> draft(
            @Valid @RequestBody PurchasingDtos.SupplierReturnRequest request) {

        branchAccess.requireAccess(request.branchId());
        SupplierReturn supplierReturn =
                returns.draft(
                        request.supplierId(),
                        request.branchId(),
                        request.grnId(),
                        request.reasonCode(),
                        request.notes(),
                        request.lines().stream()
                                .map(
                                        line ->
                                                new SupplierReturnService.ReturnLineRequest(
                                                        line.productId(),
                                                        line.sku(),
                                                        line.productName(),
                                                        line.batchNumber(),
                                                        line.quantity(),
                                                        line.unitCost()))
                                .toList());

        return ResponseEntity.created(
                        URI.create("/api/v1/supplier-returns/" + supplierReturn.getId()))
                .body(PurchasingDtos.SupplierReturnResponse.from(supplierReturn));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('purchase:receive')")
    @Operation(summary = "Record that the goods went back")
    public PurchasingDtos.SupplierReturnResponse send(@PathVariable UUID id) {
        branchAccess.requireAccess(returns.require(id).getBranchId());
        return PurchasingDtos.SupplierReturnResponse.from(returns.markSent(id));
    }

    @PostMapping("/{id}/credit")
    @PreAuthorize("hasAuthority('supplier-invoice:manage')")
    @Operation(summary = "Record the supplier's credit note")
    public PurchasingDtos.SupplierReturnResponse credit(
            @PathVariable UUID id, @Valid @RequestBody PurchasingDtos.CreditNoteRequest request) {
        branchAccess.requireAccess(returns.require(id).getBranchId());
        return PurchasingDtos.SupplierReturnResponse.from(
                returns.recordCredit(id, request.creditNoteRef()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase:receive')")
    @Operation(summary = "Abandon a return")
    public PurchasingDtos.SupplierReturnResponse cancel(@PathVariable UUID id) {
        branchAccess.requireAccess(returns.require(id).getBranchId());
        return PurchasingDtos.SupplierReturnResponse.from(returns.cancel(id));
    }
}
