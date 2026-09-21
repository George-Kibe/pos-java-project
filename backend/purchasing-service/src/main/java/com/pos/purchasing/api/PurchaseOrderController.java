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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;
import com.pos.purchasing.api.dto.PurchasingDtos;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.PurchaseOrderStatus;
import com.pos.purchasing.service.PurchaseOrderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Purchase orders, from draft to closed.
 *
 * <p>Approval is a separate endpoint on a separate permission from creation, so the person who
 * raises an order cannot be the one who authorises the spend unless a shop deliberately grants
 * both.
 */
@RestController
@RequestMapping("/api/v1/purchase-orders")
@RequiredArgsConstructor
@Tag(name = "Purchase orders")
public class PurchaseOrderController {

    private final PurchaseOrderService orders;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "Purchase orders at a branch")
    public PageResponse<PurchasingDtos.PurchaseOrderResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(required = false) PurchaseOrderStatus status,
            @PageableDefault(size = 50) Pageable pageable) {

        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                orders.list(branchId, status, pageable),
                PurchasingDtos.PurchaseOrderResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "One purchase order")
    public PurchasingDtos.PurchaseOrderResponse get(@PathVariable UUID id) {
        PurchaseOrder order = orders.require(id);
        branchAccess.requireAccess(order.getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(order);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:create')")
    @Operation(summary = "Raise a purchase order")
    public ResponseEntity<PurchasingDtos.PurchaseOrderResponse> create(
            @Valid @RequestBody PurchasingDtos.PurchaseOrderRequest request) {

        branchAccess.requireAccess(request.branchId());
        PurchaseOrder order =
                orders.create(
                        request.supplierId(),
                        request.branchId(),
                        request.expectedDeliveryDate(),
                        request.notes(),
                        request.lines().stream()
                                .map(
                                        line ->
                                                new PurchaseOrderService.OrderLineRequest(
                                                        line.productId(),
                                                        line.sku(),
                                                        line.productName(),
                                                        line.quantity(),
                                                        line.unitCost(),
                                                        line.taxRate()))
                                .toList());

        return ResponseEntity.created(URI.create("/api/v1/purchase-orders/" + order.getId()))
                .body(PurchasingDtos.PurchaseOrderResponse.from(order));
    }

    @PutMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('purchase:create')")
    @Operation(summary = "Replace a draft order's lines")
    public PurchasingDtos.PurchaseOrderResponse replaceLines(
            @PathVariable UUID id, @Valid @RequestBody PurchasingDtos.OrderLinesRequest request) {

        branchAccess.requireAccess(orders.require(id).getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(
                orders.replaceLines(
                        id,
                        request.lines().stream()
                                .map(
                                        line ->
                                                new PurchaseOrderService.OrderLineRequest(
                                                        line.productId(),
                                                        line.sku(),
                                                        line.productName(),
                                                        line.quantity(),
                                                        line.unitCost(),
                                                        line.taxRate()))
                                .toList()));
    }

    @PostMapping("/{id}/submit")
    @PreAuthorize("hasAuthority('purchase:create')")
    @Operation(summary = "Submit an order for approval")
    public PurchasingDtos.PurchaseOrderResponse submit(@PathVariable UUID id) {
        branchAccess.requireAccess(orders.require(id).getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(orders.submit(id));
    }

    @PostMapping("/{id}/return-to-draft")
    @PreAuthorize("hasAuthority('purchase:approve')")
    @Operation(summary = "Send a submitted order back to the buyer")
    public PurchasingDtos.PurchaseOrderResponse returnToDraft(@PathVariable UUID id) {
        branchAccess.requireAccess(orders.require(id).getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(orders.returnToDraft(id));
    }

    @PostMapping("/{id}/approve")
    @PreAuthorize("hasAuthority('purchase:approve')")
    @Operation(summary = "Authorise the spend")
    public PurchasingDtos.PurchaseOrderResponse approve(@PathVariable UUID id) {
        branchAccess.requireAccess(orders.require(id).getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(orders.approve(id));
    }

    @PostMapping("/{id}/send")
    @PreAuthorize("hasAuthority('purchase:create')")
    @Operation(summary = "Record that the order went to the supplier")
    public PurchasingDtos.PurchaseOrderResponse send(@PathVariable UUID id) {
        branchAccess.requireAccess(orders.require(id).getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(orders.markSent(id));
    }

    @PostMapping("/{id}/close")
    @PreAuthorize("hasAuthority('purchase:create')")
    @Operation(summary = "Close a fully received order")
    public PurchasingDtos.PurchaseOrderResponse close(@PathVariable UUID id) {
        branchAccess.requireAccess(orders.require(id).getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(orders.close(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase:create')")
    @Operation(summary = "Cancel an order that has not been delivered against")
    public PurchasingDtos.PurchaseOrderResponse cancel(
            @PathVariable UUID id, @Valid @RequestBody PurchasingDtos.CancellationRequest request) {
        branchAccess.requireAccess(orders.require(id).getBranchId());
        return PurchasingDtos.PurchaseOrderResponse.from(orders.cancel(id, request.reason()));
    }
}
