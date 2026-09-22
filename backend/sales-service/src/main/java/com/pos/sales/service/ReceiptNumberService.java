package com.pos.sales.service;

import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.sales.config.ServiceEndpointProperties;
import com.pos.sales.domain.ReceiptSequence;
import com.pos.sales.repository.ReceiptSequenceRepository;

import lombok.RequiredArgsConstructor;

/**
 * Gapless receipt numbers, per branch.
 *
 * <p>MANDATORY propagation: the number must be taken inside the transaction that records the sale.
 * Taking it in its own transaction would commit the counter even when the sale rolls back, and the
 * missing number is exactly what an audit asks about.
 *
 * <p>The counter row is locked pessimistically. Two lanes paying at the same instant must not both
 * read 4,411; an optimistic retry would also be correct but would fail a customer's checkout to
 * achieve it, and the lock is held for microseconds against only the same branch.
 */
@Service
@RequiredArgsConstructor
public class ReceiptNumberService {

    private final ReceiptSequenceRepository sequences;
    private final ServiceEndpointProperties properties;

    @Transactional(propagation = Propagation.MANDATORY)
    public String next(UUID branchId) {
        ReceiptSequence sequence =
                sequences
                        .lockForBranch(branchId)
                        .orElseGet(
                                () ->
                                        sequences.saveAndFlush(
                                                new ReceiptSequence(
                                                        branchId,
                                                        properties.receiptPrefixOrDefault())));
        String number = sequence.take();
        sequences.save(sequence);
        return number;
    }
}
