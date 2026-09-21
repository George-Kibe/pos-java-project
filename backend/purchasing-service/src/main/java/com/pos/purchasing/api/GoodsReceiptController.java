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
import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.service.GoodsReceiptService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Receiving deliveries.
 *
 * <p>Drafting and posting are separate calls because they are separate moments at the loading bay:
 * the delivery is keyed in while the lorry waits, and posted once the cartons have been checked.
 * Only posting moves stock.
 */
@RestController
@RequestMapping("/api/v1/goods-receipts")
@RequiredArgsConstructor
@Tag(name = "Goods receipts")
public class GoodsReceiptController {

    private final GoodsReceiptService receipts;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "Goods receipts at a branch")
    public PageResponse<PurchasingDtos.GoodsReceiptResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {

        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                receipts.list(branchId, pageable), PurchasingDtos.GoodsReceiptResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "One goods receipt, with its landed costs")
    public PurchasingDtos.GoodsReceiptResponse get(@PathVariable UUID id) {
        GoodsReceivedNote grn = receipts.require(id);
        branchAccess.requireAccess(grn.getBranchId());
        return PurchasingDtos.GoodsReceiptResponse.from(grn);
    }

    @PostMapping
    @PreAuthorize("hasAuthority('purchase:receive')")
    @Operation(summary = "Key in a delivery")
    public ResponseEntity<PurchasingDtos.GoodsReceiptResponse> draft(
            @Valid @RequestBody PurchasingDtos.GoodsReceiptRequest request) {

        branchAccess.requireAccess(request.branchId());
        GoodsReceivedNote grn =
                receipts.draft(
                        request.supplierId(),
                        request.branchId(),
                        request.purchaseOrderId(),
                        request.deliveryNoteRef(),
                        request.freightAmount(),
                        request.dutyAmount(),
                        request.allocationBasis(),
                        request.notes(),
                        request.lines().stream()
                                .map(
                                        line ->
                                                new GoodsReceiptService.ReceiptLineRequest(
                                                        line.productId(),
                                                        line.sku(),
                                                        line.productName(),
                                                        line.quantityReceived(),
                                                        line.quantityRejected(),
                                                        line.rejectionReason(),
                                                        line.unitCost(),
                                                        line.batchNumber(),
                                                        line.expiryDate()))
                                .toList());

        return ResponseEntity.created(URI.create("/api/v1/goods-receipts/" + grn.getId()))
                .body(PurchasingDtos.GoodsReceiptResponse.from(grn));
    }

    @PostMapping("/{id}/post")
    @PreAuthorize("hasAuthority('purchase:receive')")
    @Operation(summary = "Commit a delivery: allocate landed costs and send it to stock")
    public PurchasingDtos.GoodsReceiptResponse post(@PathVariable UUID id) {
        branchAccess.requireAccess(receipts.require(id).getBranchId());
        return PurchasingDtos.GoodsReceiptResponse.from(receipts.post(id));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('purchase:receive')")
    @Operation(summary = "Abandon a draft delivery")
    public PurchasingDtos.GoodsReceiptResponse cancel(@PathVariable UUID id) {
        branchAccess.requireAccess(receipts.require(id).getBranchId());
        return PurchasingDtos.GoodsReceiptResponse.from(receipts.cancel(id));
    }
}
