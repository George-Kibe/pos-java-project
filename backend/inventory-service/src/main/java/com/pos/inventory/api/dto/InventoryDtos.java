package com.pos.inventory.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.pos.inventory.domain.AdjustmentReason;
import com.pos.inventory.domain.LedgerDiscrepancy;
import com.pos.inventory.domain.StockAdjustment;
import com.pos.inventory.domain.StockBatch;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.StockMovement;
import com.pos.inventory.domain.StockTake;
import com.pos.inventory.domain.StockTakeLine;
import com.pos.inventory.domain.StockTransfer;

/** Wire types for the inventory API. */
public final class InventoryDtos {

    private InventoryDtos() {}

    // --- stock ------------------------------------------------------------------

    public record StockItemResponse(
            UUID id,
            UUID productId,
            String sku,
            String productName,
            String unitOfMeasure,
            UUID branchId,
            BigDecimal quantityOnHand,
            BigDecimal quantityReserved,
            /** What may still be promised: on hand minus what open carts are holding. */
            BigDecimal quantityAvailable,
            BigDecimal reorderPoint,
            boolean belowReorderPoint,
            Instant lastMovementAt) {

        public static StockItemResponse from(StockItem item) {
            return new StockItemResponse(
                    item.getId(),
                    item.getProductId(),
                    item.getSku(),
                    item.getProductName(),
                    item.getUnitOfMeasure(),
                    item.getBranchId(),
                    item.getQuantityOnHand(),
                    item.getQuantityReserved(),
                    item.quantityAvailable(),
                    item.getReorderPoint(),
                    item.isBelowReorderPoint(),
                    item.getLastMovementAt());
        }
    }

    public record BatchResponse(
            UUID id,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal quantity,
            BigDecimal unitCost,
            String currency,
            String status,
            Instant receivedAt) {

        public static BatchResponse from(StockBatch batch) {
            return new BatchResponse(
                    batch.getId(),
                    batch.getBatchNumber(),
                    batch.getExpiryDate(),
                    batch.getQuantity(),
                    batch.getUnitCost(),
                    batch.getCurrency(),
                    batch.getStatus().name(),
                    batch.getReceivedAt());
        }
    }

    public record MovementResponse(
            UUID id,
            String type,
            BigDecimal quantity,
            UUID batchId,
            String reasonCode,
            String referenceType,
            UUID referenceId,
            UUID actorId,
            Instant occurredAt) {

        public static MovementResponse from(StockMovement movement) {
            return new MovementResponse(
                    movement.getId(),
                    movement.getType().name(),
                    movement.getQuantity(),
                    movement.getBatchId(),
                    movement.getReasonCode(),
                    movement.getReferenceType(),
                    movement.getReferenceId(),
                    movement.getActorId(),
                    movement.getOccurredAt());
        }
    }

    /** One item where the cache and the ledger disagree. Should always be empty. */
    public record ReconciliationResponse(
            UUID stockItemId,
            UUID productId,
            UUID branchId,
            BigDecimal cachedQuantity,
            BigDecimal ledgerQuantity,
            BigDecimal difference) {
        public static ReconciliationResponse from(LedgerDiscrepancy row) {
            return new ReconciliationResponse(
                    row.stockItemId(),
                    row.productId(),
                    row.branchId(),
                    row.cachedQuantity(),
                    row.ledgerQuantity(),
                    row.drift());
        }
    }

    public record ReorderPointRequest(BigDecimal reorderPoint, BigDecimal reorderQuantity) {}

    // --- adjustments ------------------------------------------------------------

    public record AdjustmentLineRequest(
            @NotNull UUID productId,
            String sku,
            /** Signed: negative writes stock off, positive puts it on. */
            @NotNull BigDecimal quantityDelta,
            @Size(max = 255) String notes) {}

    public record AdjustmentRequest(
            @NotNull UUID branchId,
            @NotNull AdjustmentReason reasonCode,
            String notes,
            @NotEmpty List<AdjustmentLineRequest> lines) {}

    public record AdjustmentResponse(
            UUID id,
            UUID branchId,
            String reasonCode,
            String status,
            String notes,
            Instant postedAt,
            UUID postedBy,
            List<AdjustmentLineResponse> lines) {

        public static AdjustmentResponse from(StockAdjustment adjustment) {
            return new AdjustmentResponse(
                    adjustment.getId(),
                    adjustment.getBranchId(),
                    adjustment.getReasonCode().name(),
                    adjustment.getStatus().name(),
                    adjustment.getNotes(),
                    adjustment.getPostedAt(),
                    adjustment.getPostedBy(),
                    adjustment.getLines().stream()
                            .map(
                                    line ->
                                            new AdjustmentLineResponse(
                                                    line.getId(),
                                                    line.getStockItem().getProductId(),
                                                    line.getStockItem().getSku(),
                                                    line.getQuantityDelta(),
                                                    line.getNotes()))
                            .toList());
        }
    }

    public record AdjustmentLineResponse(
            UUID id, UUID productId, String sku, BigDecimal quantityDelta, String notes) {}

    // --- stock takes ------------------------------------------------------------

    public record StockTakeRequest(
            @NotBlank @Size(max = 50) String reference, @NotNull UUID branchId, String notes) {}

    public record CountLineRequest(
            @NotNull UUID stockItemId, @NotNull BigDecimal countedQuantity, String notes) {}

    public record CountRequest(@NotEmpty List<CountLineRequest> counts) {}

    public record StockTakeResponse(
            UUID id,
            String reference,
            UUID branchId,
            String status,
            Instant snapshotAt,
            Instant postedAt,
            int lineCount,
            int countedCount,
            int varianceCount,
            List<StockTakeLineResponse> lines) {

        public static StockTakeResponse from(StockTake stockTake, boolean includeLines) {
            List<StockTakeLine> lines = stockTake.getLines();
            return new StockTakeResponse(
                    stockTake.getId(),
                    stockTake.getReference(),
                    stockTake.getBranchId(),
                    stockTake.getStatus().name(),
                    stockTake.getSnapshotAt(),
                    stockTake.getPostedAt(),
                    lines.size(),
                    (int) lines.stream().filter(StockTakeLine::isCounted).count(),
                    (int) lines.stream().filter(StockTakeLine::hasVariance).count(),
                    includeLines
                            ? lines.stream().map(StockTakeLineResponse::from).toList()
                            : List.of());
        }
    }

    public record StockTakeLineResponse(
            UUID id,
            UUID stockItemId,
            UUID productId,
            String sku,
            BigDecimal snapshotQuantity,
            BigDecimal countedQuantity,
            BigDecimal variance,
            String notes) {

        static StockTakeLineResponse from(StockTakeLine line) {
            return new StockTakeLineResponse(
                    line.getId(),
                    line.getStockItem().getId(),
                    line.getProductId(),
                    line.getSku(),
                    line.getSnapshotQuantity(),
                    line.getCountedQuantity(),
                    line.variance(),
                    line.getNotes());
        }
    }

    // --- transfers --------------------------------------------------------------

    public record TransferLineRequest(
            @NotNull UUID productId, String sku, @NotNull @Positive BigDecimal quantity) {}

    public record TransferRequest(
            @NotBlank @Size(max = 50) String reference,
            @NotNull UUID fromBranchId,
            @NotNull UUID toBranchId,
            String notes,
            @NotEmpty List<TransferLineRequest> lines) {}

    public record ReceiveLineRequest(@NotNull UUID lineId, @NotNull BigDecimal quantityReceived) {}

    public record ReceiveRequest(List<ReceiveLineRequest> lines) {}

    public record TransferResponse(
            UUID id,
            String reference,
            UUID fromBranchId,
            UUID toBranchId,
            String status,
            Instant dispatchedAt,
            Instant receivedAt,
            List<TransferLineResponse> lines) {

        public static TransferResponse from(StockTransfer transfer) {
            return new TransferResponse(
                    transfer.getId(),
                    transfer.getReference(),
                    transfer.getFromBranchId(),
                    transfer.getToBranchId(),
                    transfer.getStatus().name(),
                    transfer.getDispatchedAt(),
                    transfer.getReceivedAt(),
                    transfer.getLines().stream()
                            .map(
                                    line ->
                                            new TransferLineResponse(
                                                    line.getId(),
                                                    line.getProductId(),
                                                    line.getSku(),
                                                    line.getQuantitySent(),
                                                    line.getQuantityReceived()))
                            .toList());
        }
    }

    public record TransferLineResponse(
            UUID id,
            UUID productId,
            String sku,
            BigDecimal quantitySent,
            BigDecimal quantityReceived) {}

    // --- reservations -----------------------------------------------------------

    public record ReservationRequest(
            @NotNull UUID productId,
            @NotNull UUID branchId,
            @NotNull @Positive BigDecimal quantity,
            @NotBlank String referenceType,
            @NotNull UUID referenceId) {}

    public record ReservationResponse(
            UUID id, UUID productId, BigDecimal quantity, String status, Instant expiresAt) {}
}
