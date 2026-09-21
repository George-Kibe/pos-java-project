package com.pos.inventory.api;

import java.net.URI;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;
import com.pos.inventory.api.dto.InventoryDtos;
import com.pos.inventory.domain.StockReservation;
import com.pos.inventory.service.AdjustmentService;
import com.pos.inventory.service.ReservationService;
import com.pos.inventory.service.StockTakeService;
import com.pos.inventory.service.TransferService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Adjustments, stock takes, transfers and reservations. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Inventory operations")
public class InventoryOperationsController {

    private final AdjustmentService adjustments;
    private final StockTakeService stockTakes;
    private final TransferService transfers;
    private final ReservationService reservations;
    private final BranchAccessGuard branchAccess;

    // --- adjustments ------------------------------------------------------------

    @GetMapping("/adjustments")
    @PreAuthorize("hasAuthority('inventory:view')")
    @Operation(summary = "Adjustments at a branch")
    public PageResponse<InventoryDtos.AdjustmentResponse> listAdjustments(
            @RequestParam UUID branchId, @PageableDefault(size = 25) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                adjustments.list(branchId, pageable), InventoryDtos.AdjustmentResponse::from);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('inventory:adjust')")
    @Operation(summary = "Draft an adjustment; it changes nothing until posted")
    public ResponseEntity<InventoryDtos.AdjustmentResponse> draftAdjustment(
            @Valid @RequestBody InventoryDtos.AdjustmentRequest request) {

        branchAccess.requireAccess(request.branchId());
        var drafted =
                adjustments.draft(
                        request.branchId(),
                        request.reasonCode(),
                        request.notes(),
                        request.lines().stream()
                                .map(
                                        line ->
                                                new AdjustmentService.AdjustmentLineRequest(
                                                        line.productId(),
                                                        line.sku(),
                                                        line.quantityDelta(),
                                                        line.notes()))
                                .toList());

        InventoryDtos.AdjustmentResponse body = InventoryDtos.AdjustmentResponse.from(drafted);
        return ResponseEntity.created(URI.create("/api/v1/adjustments/" + body.id())).body(body);
    }

    @PostMapping("/adjustments/{id}/post")
    @PreAuthorize("hasAuthority('inventory:adjust')")
    @Operation(summary = "Post an adjustment, writing its movements")
    public InventoryDtos.AdjustmentResponse postAdjustment(@PathVariable UUID id) {
        return InventoryDtos.AdjustmentResponse.from(adjustments.post(id));
    }

    @PostMapping("/adjustments/{id}/cancel")
    @PreAuthorize("hasAuthority('inventory:adjust')")
    @Operation(summary = "Cancel a drafted adjustment")
    public InventoryDtos.AdjustmentResponse cancelAdjustment(@PathVariable UUID id) {
        return InventoryDtos.AdjustmentResponse.from(adjustments.cancel(id));
    }

    // --- stock takes ------------------------------------------------------------

    @PostMapping("/stock-takes")
    @PreAuthorize("hasAuthority('stocktake:manage')")
    @Operation(summary = "Open a count and snapshot what the system believes")
    public ResponseEntity<InventoryDtos.StockTakeResponse> openStockTake(
            @Valid @RequestBody InventoryDtos.StockTakeRequest request) {

        branchAccess.requireAccess(request.branchId());
        var opened = stockTakes.open(request.reference(), request.branchId(), request.notes());
        InventoryDtos.StockTakeResponse body = InventoryDtos.StockTakeResponse.from(opened, false);
        return ResponseEntity.created(URI.create("/api/v1/stock-takes/" + body.id())).body(body);
    }

    @GetMapping("/stock-takes/{id}")
    @PreAuthorize("hasAuthority('stocktake:manage')")
    @Operation(summary = "A count sheet with its variances")
    public InventoryDtos.StockTakeResponse getStockTake(@PathVariable UUID id) {
        return InventoryDtos.StockTakeResponse.from(stockTakes.get(id), true);
    }

    @PostMapping("/stock-takes/{id}/counts")
    @PreAuthorize("hasAuthority('stocktake:manage')")
    @Operation(summary = "Record counted quantities")
    public InventoryDtos.StockTakeResponse recordCounts(
            @PathVariable UUID id, @Valid @RequestBody InventoryDtos.CountRequest request) {

        var counted =
                stockTakes.count(
                        id,
                        request.counts().stream()
                                .map(
                                        line ->
                                                new StockTakeService.CountLine(
                                                        line.stockItemId(),
                                                        line.countedQuantity(),
                                                        line.notes()))
                                .toList());
        return InventoryDtos.StockTakeResponse.from(counted, true);
    }

    @PostMapping("/stock-takes/{id}/review")
    @PreAuthorize("hasAuthority('stocktake:manage')")
    @Operation(summary = "Submit a count for review before posting")
    public InventoryDtos.StockTakeResponse submitForReview(@PathVariable UUID id) {
        return InventoryDtos.StockTakeResponse.from(stockTakes.submitForReview(id), true);
    }

    @PostMapping("/stock-takes/{id}/post")
    @PreAuthorize("hasAuthority('stocktake:manage')")
    @Operation(summary = "Post the variances as stock movements")
    public InventoryDtos.StockTakeResponse postStockTake(@PathVariable UUID id) {
        return InventoryDtos.StockTakeResponse.from(stockTakes.post(id), true);
    }

    // --- transfers --------------------------------------------------------------

    @PostMapping("/transfers")
    @PreAuthorize("hasAuthority('transfer:manage')")
    @Operation(summary = "Draft a transfer between branches")
    public ResponseEntity<InventoryDtos.TransferResponse> draftTransfer(
            @Valid @RequestBody InventoryDtos.TransferRequest request) {

        branchAccess.requireAccess(request.fromBranchId());
        var drafted =
                transfers.draft(
                        request.reference(),
                        request.fromBranchId(),
                        request.toBranchId(),
                        request.notes(),
                        request.lines().stream()
                                .map(
                                        line ->
                                                new TransferService.TransferLineRequest(
                                                        line.productId(),
                                                        line.sku(),
                                                        line.quantity()))
                                .toList());

        InventoryDtos.TransferResponse body = InventoryDtos.TransferResponse.from(drafted);
        return ResponseEntity.created(URI.create("/api/v1/transfers/" + body.id())).body(body);
    }

    @PostMapping("/transfers/{id}/dispatch")
    @PreAuthorize("hasAuthority('transfer:manage')")
    @Operation(summary = "Send a transfer: deducts from the sender, now in transit")
    public InventoryDtos.TransferResponse dispatchTransfer(@PathVariable UUID id) {
        return InventoryDtos.TransferResponse.from(transfers.dispatch(id));
    }

    @PostMapping("/transfers/{id}/receive")
    @PreAuthorize("hasAuthority('transfer:manage')")
    @Operation(summary = "Receive a transfer; a line may be received short")
    public InventoryDtos.TransferResponse receiveTransfer(
            @PathVariable UUID id,
            @RequestBody(required = false) InventoryDtos.ReceiveRequest request) {

        List<TransferService.ReceiptLineRequest> lines =
                request == null || request.lines() == null
                        ? List.of()
                        : request.lines().stream()
                                .map(
                                        line ->
                                                new TransferService.ReceiptLineRequest(
                                                        line.lineId(), line.quantityReceived()))
                                .toList();
        return InventoryDtos.TransferResponse.from(transfers.receive(id, lines));
    }

    // --- reservations -----------------------------------------------------------

    @PostMapping("/reservations")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Hold stock for an open cart")
    public InventoryDtos.ReservationResponse reserve(
            @Valid @RequestBody InventoryDtos.ReservationRequest request) {

        branchAccess.requireAccess(request.branchId());
        StockReservation reservation =
                reservations.reserve(
                        request.productId(),
                        request.branchId(),
                        request.quantity(),
                        request.referenceType(),
                        request.referenceId());

        return new InventoryDtos.ReservationResponse(
                reservation.getId(),
                request.productId(),
                reservation.getQuantity(),
                reservation.getStatus().name(),
                reservation.getExpiresAt());
    }

    @DeleteMapping("/reservations")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Return held stock to sale")
    public Map<String, Integer> release(
            @RequestParam String referenceType, @RequestParam UUID referenceId) {
        return Map.of("released", reservations.release(referenceType, referenceId));
    }
}
