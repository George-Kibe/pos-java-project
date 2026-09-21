package com.pos.purchasing.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.domain.ReturnReason;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierReturn;
import com.pos.purchasing.domain.SupplierReturnStatus;
import com.pos.purchasing.repository.SupplierReturnRepository;

import lombok.RequiredArgsConstructor;

/**
 * Sending goods back.
 *
 * <p>Priced at the cost the goods were received at, taken from the receipt where one is referenced.
 * Using today's cost would credit the wrong amount whenever a price has moved since the delivery,
 * and it moves often.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SupplierReturnService {

    private final SupplierReturnRepository returns;
    private final SupplierService suppliers;
    private final GoodsReceiptService receipts;
    private final DocumentNumberService numbers;

    /** One product going back. A null unit cost is taken from the referenced receipt. */
    public record ReturnLineRequest(
            UUID productId,
            String sku,
            String productName,
            String batchNumber,
            BigDecimal quantity,
            BigDecimal unitCost) {}

    public Page<SupplierReturn> list(UUID branchId, Pageable pageable) {
        return returns.findByBranchId(branchId, pageable);
    }

    public SupplierReturn require(UUID id) {
        return returns.findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Supplier return", id));
    }

    @Transactional
    public SupplierReturn draft(
            UUID supplierId,
            UUID branchId,
            UUID grnId,
            ReturnReason reasonCode,
            String notes,
            List<ReturnLineRequest> lines) {

        if (lines == null || lines.isEmpty()) {
            throw new Errors.BusinessRuleException(
                    "supplier_return.no_lines", "A return needs at least one line");
        }

        Supplier supplier = suppliers.require(supplierId);
        SupplierReturn supplierReturn =
                new SupplierReturn(numbers.nextReturnNumber(), supplier, branchId, reasonCode);
        supplierReturn.setNotes(notes);

        GoodsReceivedNote grn = null;
        if (grnId != null) {
            grn = receipts.require(grnId);
            if (!grn.isPosted()) {
                throw new Errors.BusinessRuleException(
                        "supplier_return.grn_not_posted",
                        "Goods receipt %s has not been posted, so nothing from it is in stock"
                                .formatted(grn.getGrnNumber()));
            }
            supplierReturn.setGrn(grn);
        }

        for (ReturnLineRequest line : lines) {
            BigDecimal unitCost = line.unitCost() != null ? line.unitCost() : costFrom(grn, line);
            if (unitCost == null) {
                throw new Errors.BusinessRuleException(
                        "supplier_return.unknown_cost",
                        "No cost for %s: give one, or reference the receipt it came in on"
                                .formatted(line.sku()));
            }
            supplierReturn.addLine(
                    line.productId(),
                    line.sku(),
                    line.productName(),
                    line.batchNumber(),
                    line.quantity(),
                    unitCost);
        }

        supplierReturn.recalculateTotal();
        return returns.save(supplierReturn);
    }

    @Transactional
    public SupplierReturn markSent(UUID id) {
        SupplierReturn supplierReturn = transition(require(id), SupplierReturnStatus.SENT);
        supplierReturn.setSentAt(Instant.now());
        return returns.save(supplierReturn);
    }

    /** The supplier's credit note has arrived; the return is settled. */
    @Transactional
    public SupplierReturn recordCredit(UUID id, String creditNoteRef) {
        SupplierReturn supplierReturn = transition(require(id), SupplierReturnStatus.CREDITED);
        supplierReturn.setCreditedAt(Instant.now());
        supplierReturn.setCreditNoteRef(creditNoteRef);
        return returns.save(supplierReturn);
    }

    @Transactional
    public SupplierReturn cancel(UUID id) {
        return returns.save(transition(require(id), SupplierReturnStatus.CANCELLED));
    }

    /**
     * The landed cost the goods came in at.
     *
     * <p>Landed rather than invoiced: that is what the stock was valued at, and crediting the
     * invoice price would leave the freight share sitting in inventory against goods that have
     * left.
     */
    private static BigDecimal costFrom(GoodsReceivedNote grn, ReturnLineRequest line) {
        if (grn == null) {
            return null;
        }
        return grn.getLines().stream()
                .filter(candidate -> candidate.getProductId().equals(line.productId()))
                .findFirst()
                .map(
                        candidate ->
                                candidate.getLandedUnitCost() != null
                                        ? candidate.getLandedUnitCost()
                                        : candidate.getUnitCost())
                .orElse(null);
    }

    private SupplierReturn transition(SupplierReturn supplierReturn, SupplierReturnStatus next) {
        if (!supplierReturn.getStatus().canTransitionTo(next)) {
            throw new Errors.ConflictException(
                    "supplier_return.illegal_transition",
                    "A %s return cannot become %s".formatted(supplierReturn.getStatus(), next));
        }
        supplierReturn.setStatus(next);
        return supplierReturn;
    }
}
