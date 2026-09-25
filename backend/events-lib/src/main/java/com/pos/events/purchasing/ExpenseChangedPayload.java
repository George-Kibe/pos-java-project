package com.pos.events.purchasing;

import java.math.BigDecimal;
import java.time.LocalDate;
import java.util.UUID;

/**
 * An expense as it now stands: recorded, approved, rejected or voided.
 *
 * <p>The whole expense every time, with a {@code revision} that only goes up, so a reader keeps the
 * highest revision it has seen and events arriving out of order change nothing. Only an {@code
 * APPROVED} expense counts against profit.
 *
 * @param branchId the branch it was spent for; null for head office
 * @param category RENT, WAGES, ELECTRICITY, WATER, TRANSPORT, SECURITY, REPAIRS, COMMUNICATION,
 *     LICENCES, BANK_CHARGES or OTHER
 * @param incurredOn the business day it belongs to
 * @param amount without VAT
 * @param taxAmount the VAT on it, reclaimable
 * @param status PENDING_APPROVAL, APPROVED, REJECTED or VOIDED
 */
public record ExpenseChangedPayload(
        UUID expenseId,
        String expenseNumber,
        UUID branchId,
        String category,
        String description,
        LocalDate incurredOn,
        BigDecimal amount,
        BigDecimal taxAmount,
        String currency,
        String status,
        long revision) {}
