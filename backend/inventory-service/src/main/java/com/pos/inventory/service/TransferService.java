package com.pos.inventory.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.inventory.domain.MovementType;
import com.pos.inventory.domain.StockItem;
import com.pos.inventory.domain.StockTransfer;
import com.pos.inventory.domain.StockTransferLine;
import com.pos.inventory.repository.StockTransferRepository;

import lombok.RequiredArgsConstructor;

/**
 * Moving stock between branches.
 *
 * <p>Two steps, not one. Dispatch deducts from the sender; receipt adds to the receiver. In between
 * the stock belongs to neither shelf, which is the truth - it is in a van. Treating a transfer as a
 * single instant move means the goods appear at the destination before they arrive, and a branch
 * can sell something that is still in traffic.
 */
@Service
@RequiredArgsConstructor
public class TransferService {

    private final StockTransferRepository transfers;
    private final StockService stock;
    private final BatchConsumer batchConsumer;

    public record TransferLineRequest(UUID productId, String sku, BigDecimal quantity) {}

    public record ReceiptLineRequest(UUID lineId, BigDecimal quantityReceived) {}

    @Transactional(readOnly = true)
    public Page<StockTransfer> list(UUID branchId, Pageable pageable) {
        return transfers.findInvolvingBranch(branchId, pageable);
    }

    @Transactional(readOnly = true)
    public StockTransfer get(UUID id) {
        return transfers
                .findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Transfer", id));
    }

    @Transactional
    public StockTransfer draft(
            String reference,
            UUID fromBranchId,
            UUID toBranchId,
            String notes,
            List<TransferLineRequest> lines) {

        if (fromBranchId.equals(toBranchId)) {
            throw new Errors.BadRequestException(
                    "transfer.same_branch", "A transfer needs two different branches.");
        }
        if (transfers.existsByReference(reference)) {
            throw new Errors.ConflictException(
                    "transfer.reference_taken", "A transfer with that reference already exists.");
        }
        if (lines == null || lines.isEmpty()) {
            throw new Errors.BadRequestException(
                    "transfer.no_lines", "A transfer needs at least one line.");
        }

        StockTransfer transfer = new StockTransfer(reference, fromBranchId, toBranchId);
        transfer.setNotes(notes);
        for (TransferLineRequest line : lines) {
            transfer.getLines()
                    .add(
                            new StockTransferLine(
                                    transfer, line.productId(), line.sku(), line.quantity()));
        }
        return transfers.save(transfer);
    }

    /** Takes the stock off the sending branch's shelf. It is now in transit. */
    @Transactional
    public StockTransfer dispatch(UUID transferId) {
        StockTransfer transfer = get(transferId);
        if (transfer.getStatus() != StockTransfer.Status.DRAFT) {
            throw new Errors.ConflictException(
                    "transfer.not_draft",
                    "This transfer is %s and cannot be dispatched."
                            .formatted(transfer.getStatus()));
        }

        UUID actor = AuthenticatedUser.currentUserId();

        for (StockTransferLine line : transfer.getLines()) {
            StockItem item = stock.require(line.getProductId(), transfer.getFromBranchId());

            BigDecimal shortfall =
                    batchConsumer.consume(
                            item,
                            line.getQuantitySent(),
                            new StockLedgerService.MovementContext(
                                    MovementType.TRANSFER_OUT,
                                    null,
                                    "StockTransfer",
                                    transfer.getId(),
                                    actor,
                                    Instant.now()));

            if (shortfall.signum() > 0) {
                // Unlike a sale, nothing has physically happened yet: somebody is about to load a
                // van with stock the branch does not have, and they should hear about it now.
                throw new Errors.BusinessRuleException(
                        "transfer.insufficient_stock",
                        "Branch does not hold enough of %s to send %s."
                                .formatted(line.getSku(), line.getQuantitySent()));
            }
        }

        transfer.setStatus(StockTransfer.Status.IN_TRANSIT);
        transfer.setDispatchedAt(Instant.now());
        transfer.setDispatchedBy(actor);
        return transfers.save(transfer);
    }

    /**
     * Puts the stock on the receiving branch's shelf.
     *
     * <p>A line may be received short. The difference is recorded as received rather than quietly
     * topped up to what was sent, because the gap is a real event - stock that left one branch and
     * did not arrive at the other is exactly what somebody needs to investigate.
     */
    @Transactional
    public StockTransfer receive(UUID transferId, List<ReceiptLineRequest> received) {
        StockTransfer transfer = get(transferId);
        if (transfer.getStatus() != StockTransfer.Status.IN_TRANSIT) {
            throw new Errors.ConflictException(
                    "transfer.not_in_transit",
                    "This transfer is %s and cannot be received.".formatted(transfer.getStatus()));
        }

        UUID actor = AuthenticatedUser.currentUserId();

        for (StockTransferLine line : transfer.getLines()) {
            BigDecimal quantity =
                    received.stream()
                            .filter(entry -> entry.lineId().equals(line.getId()))
                            .map(ReceiptLineRequest::quantityReceived)
                            .findFirst()
                            // Nothing said about this line means it all arrived.
                            .orElse(line.getQuantitySent());

            line.setQuantityReceived(quantity);
            if (quantity.signum() <= 0) {
                continue;
            }

            StockItem item =
                    stock.findOrCreateItem(
                            line.getProductId(), transfer.getToBranchId(), line.getSku());

            batchConsumer.addUntrackedStock(
                    item,
                    quantity,
                    BatchConsumer.syntheticBatchNumber("TRF", transfer.getReference()),
                    line.getExpiryDate(),
                    line.getUnitCost(),
                    new StockLedgerService.MovementContext(
                            MovementType.TRANSFER_IN,
                            null,
                            "StockTransfer",
                            transfer.getId(),
                            actor,
                            Instant.now()));
        }

        transfer.setStatus(StockTransfer.Status.RECEIVED);
        transfer.setReceivedAt(Instant.now());
        transfer.setReceivedBy(actor);
        return transfers.save(transfer);
    }
}
