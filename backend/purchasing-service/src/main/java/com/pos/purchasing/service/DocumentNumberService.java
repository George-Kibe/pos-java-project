package com.pos.purchasing.service;

import java.time.LocalDate;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.purchasing.repository.GoodsReceivedNoteRepository;
import com.pos.purchasing.repository.PurchaseOrderRepository;
import com.pos.purchasing.repository.SupplierReturnRepository;

import lombok.RequiredArgsConstructor;

/**
 * Human-readable document numbers: {@code PO-2026-000042}.
 *
 * <p>A UUID is the primary key, but nobody reads a UUID down the phone to a supplier. The sequence
 * is derived from the highest number already issued this year, which is simple and correct under
 * the one transaction per document that actually happens here - and the unique constraint on the
 * column is what makes a collision a loud failure rather than a duplicate.
 */
@Service
@RequiredArgsConstructor
public class DocumentNumberService {

    private final PurchaseOrderRepository orders;
    private final GoodsReceivedNoteRepository grns;
    private final SupplierReturnRepository returns;
    private final com.pos.purchasing.repository.ExpenseRepository expenses;

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextPurchaseOrderNumber() {
        String prefix = "PO-" + year() + "-";
        return prefix + format(orders.highestSequenceFor(prefix) + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextGrnNumber() {
        String prefix = "GRN-" + year() + "-";
        return prefix + format(grns.highestSequenceFor(prefix) + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextReturnNumber() {
        String prefix = "SR-" + year() + "-";
        return prefix + format(returns.highestSequenceFor(prefix) + 1);
    }

    @Transactional(propagation = Propagation.MANDATORY)
    public String nextExpenseNumber() {
        String prefix = "EXP-" + year() + "-";
        return prefix + format(expenses.highestSequenceFor(prefix) + 1);
    }

    private int year() {
        return LocalDate.now().getYear();
    }

    private static String format(int sequence) {
        return "%06d".formatted(sequence);
    }
}
