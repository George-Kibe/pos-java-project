package com.pos.inventory.api;

import java.util.List;
import java.util.Map;
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
import com.pos.inventory.api.dto.InventoryDtos;
import com.pos.inventory.service.InventorySweepService;
import com.pos.inventory.service.StockQueryService;
import com.pos.inventory.service.StockService;
import com.pos.inventory.service.StockValuationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** What a branch holds, and how it got there. */
@RestController
@RequestMapping("/api/v1/stock")
@RequiredArgsConstructor
@Tag(name = "Stock")
public class StockController {

    private final StockQueryService query;
    private final StockService stock;
    private final InventorySweepService sweeps;
    private final StockValuationService valuations;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(summary = "Stock held at a branch")
    public PageResponse<InventoryDtos.StockItemResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {
        // A stock report is branch-scoped data; a cashier at one branch must not read another's.
        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                query.atBranch(branchId, pageable), InventoryDtos.StockItemResponse::from);
    }

    @GetMapping("/{productId}")
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(summary = "One product's stock at a branch")
    public InventoryDtos.StockItemResponse get(
            @PathVariable UUID productId, @RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return InventoryDtos.StockItemResponse.from(query.one(productId, branchId));
    }

    @GetMapping("/{productId}/batches")
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(summary = "A product's batches, in the order they will be sold")
    public List<InventoryDtos.BatchResponse> batches(
            @PathVariable UUID productId, @RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return query.batchesOf(productId, branchId).stream()
                .map(InventoryDtos.BatchResponse::from)
                .toList();
    }

    @GetMapping("/{productId}/movements")
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(summary = "The ledger for a product at a branch")
    public PageResponse<InventoryDtos.MovementResponse> movements(
            @PathVariable UUID productId,
            @RequestParam UUID branchId,
            @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                query.movementsOf(productId, branchId, pageable),
                InventoryDtos.MovementResponse::from);
    }

    @GetMapping("/expiring")
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(
            summary =
                    "Batches at a branch expiring within the next days, soonest first - already"
                            + " expired included - with their value at cost")
    public List<InventoryDtos.ExpiringBatchResponse> expiring(
            @RequestParam UUID branchId,
            @RequestParam(defaultValue = "30")
                    @jakarta.validation.constraints.Min(0)
                    @jakarta.validation.constraints.Max(365)
                    int days) {
        branchAccess.requireAccess(branchId);
        java.time.LocalDate today = query.today();
        return query.expiringAt(branchId, days).stream()
                .map(batch -> InventoryDtos.ExpiringBatchResponse.from(batch, today))
                .toList();
    }

    @GetMapping("/low")
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(summary = "Products at or below their reorder point")
    public List<InventoryDtos.StockItemResponse> lowStock(@RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return query.belowReorderPoint(branchId).stream()
                .map(InventoryDtos.StockItemResponse::from)
                .toList();
    }

    @PutMapping("/{productId}/reorder-point")
    @PreAuthorize("hasAuthority('inventory:adjust')")
    @Operation(summary = "Set a product's reorder point at a branch")
    public InventoryDtos.StockItemResponse setReorderPoint(
            @PathVariable UUID productId,
            @RequestParam UUID branchId,
            @Valid @RequestBody InventoryDtos.ReorderPointRequest request) {

        branchAccess.requireAccess(branchId);
        return InventoryDtos.StockItemResponse.from(
                stock.setReorderPoint(
                        productId, branchId, request.reorderPoint(), request.reorderQuantity()));
    }

    /**
     * Every item whose cached quantity disagrees with its movements.
     *
     * <p>Should always be empty. Exposed so an operator can ask rather than wait for the nightly
     * check, because the answer to "can I trust these numbers" should not require reading logs.
     */
    @GetMapping("/reconciliation")
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(summary = "Items where the ledger and the cached quantity disagree")
    public List<InventoryDtos.ReconciliationResponse> reconciliation() {
        return sweeps.reconcileLedger().stream()
                .map(InventoryDtos.ReconciliationResponse::from)
                .toList();
    }

    @PostMapping("/valuations")
    @PreAuthorize("hasAuthority('inventory:adjust')")
    @Operation(
            summary =
                    "Value a branch's stock now and announce it; reporting picks the snapshot up"
                            + " as it does the nightly one")
    public ResponseEntity<Map<String, UUID>> valueNow(@RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return ResponseEntity.accepted()
                .body(Map.of("snapshotId", valuations.valueBranch(branchId)));
    }
}
