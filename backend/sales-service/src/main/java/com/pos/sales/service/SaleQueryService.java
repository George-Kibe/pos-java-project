package com.pos.sales.service;

import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.sales.domain.Sale;
import com.pos.sales.repository.SaleRepository;

import lombok.RequiredArgsConstructor;

/** Read-only lookups, so a controller never reaches into a repository. */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class SaleQueryService {

    private final SaleRepository sales;

    public Page<Sale> atBranch(UUID branchId, Pageable pageable) {
        return sales.findByBranchIdOrderByOccurredAtDesc(branchId, pageable);
    }

    public Sale byReceiptNumber(UUID branchId, String receiptNumber) {
        return sales.findByBranchIdAndReceiptNumber(branchId, receiptNumber)
                .orElseThrow(() -> Errors.NotFoundException.of("Receipt", receiptNumber));
    }

    /** Every branch's sale with this number: each branch numbers its receipts from R-000001. */
    public List<Sale> allWithReceiptNumber(String receiptNumber) {
        return sales.findByReceiptNumber(receiptNumber);
    }
}
