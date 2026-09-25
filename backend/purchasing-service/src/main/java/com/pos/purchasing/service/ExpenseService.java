package com.pos.purchasing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.purchasing.domain.Expense;
import com.pos.purchasing.domain.ExpenseCategory;
import com.pos.purchasing.domain.ExpenseSettings;
import com.pos.purchasing.domain.ExpenseStatus;
import com.pos.purchasing.messaging.PurchasingEventPublisher;
import com.pos.purchasing.repository.ExpenseRepository;
import com.pos.purchasing.repository.ExpenseSettingsRepository;

import lombok.RequiredArgsConstructor;

/**
 * The expenses register: what running the shops costs beyond the goods.
 *
 * <p>An expense above the approval limit counts only once someone other than whoever recorded it
 * approves it - the same rule as cash moving to the intraday. Nothing is ever deleted: a mistake is
 * voided with a reason, and every change is announced so reporting's profit and loss follows.
 */
@Service
@RequiredArgsConstructor
public class ExpenseService {

    private final ExpenseRepository expenses;
    private final ExpenseSettingsRepository settings;
    private final DocumentNumberService numbers;
    private final PurchasingEventPublisher events;

    public record NewExpense(
            UUID branchId,
            ExpenseCategory category,
            String description,
            String payee,
            String reference,
            LocalDate incurredOn,
            BigDecimal amount,
            BigDecimal taxAmount) {}

    @Transactional(readOnly = true)
    public ExpenseSettings settings() {
        return settings.findFirstByOrderByCreatedAtAsc()
                .orElseThrow(
                        () ->
                                new IllegalStateException(
                                        "expense_settings has no row; the migration seeds one"));
    }

    @Transactional
    public ExpenseSettings setApprovalLimit(BigDecimal limit) {
        if (limit == null || limit.signum() < 0) {
            throw new Errors.BadRequestException(
                    "expense.invalid_limit", "The approval limit cannot be negative.");
        }
        ExpenseSettings current = settings();
        current.setApprovalLimit(limit.setScale(4, RoundingMode.HALF_UP));
        return settings.save(current);
    }

    @Transactional
    public Expense record(NewExpense request) {
        if (request.amount() == null || request.amount().signum() <= 0) {
            throw new Errors.BadRequestException(
                    "expense.invalid_amount", "An expense is more than zero.");
        }
        if (request.incurredOn() == null
                || request.incurredOn().isAfter(LocalDate.now().plusDays(1))) {
            throw new Errors.BadRequestException(
                    "expense.future_date", "An expense is recorded once it has been incurred.");
        }
        BigDecimal limit = settings().getApprovalLimit();
        Expense expense = new Expense();
        expense.setExpenseNumber(numbers.nextExpenseNumber());
        expense.setBranchId(request.branchId());
        expense.setCategory(request.category());
        expense.setDescription(request.description().strip());
        expense.setPayee(blankToNull(request.payee()));
        expense.setReference(blankToNull(request.reference()));
        expense.setIncurredOn(request.incurredOn());
        expense.setAmount(request.amount().setScale(4, RoundingMode.HALF_UP));
        expense.setTaxAmount(
                request.taxAmount() == null
                        ? BigDecimal.ZERO
                        : request.taxAmount().setScale(4, RoundingMode.HALF_UP));
        expense.setRecordedBy(AuthenticatedUser.currentUserId());
        expense.setRecordedAt(Instant.now());
        boolean large = expense.getAmount().compareTo(limit) > 0;
        expense.setNeedsApproval(large);
        expense.setStatus(large ? ExpenseStatus.PENDING_APPROVAL : ExpenseStatus.APPROVED);
        return announced(expense);
    }

    @Transactional(readOnly = true)
    public Expense require(UUID id) {
        return expenses.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Expense", id));
    }

    @Transactional(readOnly = true)
    public Page<Expense> list(
            UUID branchId, LocalDate from, LocalDate to, ExpenseStatus status, Pageable pageable) {
        if (branchId == null) {
            return status == null
                    ? expenses.findByBranchIdIsNullAndIncurredOnBetween(from, to, pageable)
                    : expenses.findByBranchIdIsNullAndIncurredOnBetweenAndStatus(
                            from, to, status, pageable);
        }
        return status == null
                ? expenses.findByBranchIdAndIncurredOnBetween(branchId, from, to, pageable)
                : expenses.findByBranchIdAndIncurredOnBetweenAndStatus(
                        branchId, from, to, status, pageable);
    }

    /** Approved by someone other than whoever recorded it; then it counts. */
    @Transactional
    public Expense approve(UUID id) {
        Expense expense = requirePending(id);
        UUID approver = requireSomeoneElse(expense);
        expense.setStatus(ExpenseStatus.APPROVED);
        expense.setDecidedBy(approver);
        expense.setDecidedAt(Instant.now());
        return announced(expense);
    }

    @Transactional
    public Expense reject(UUID id, String reason) {
        Expense expense = requirePending(id);
        UUID approver = requireSomeoneElse(expense);
        expense.setStatus(ExpenseStatus.REJECTED);
        expense.setDecidedBy(approver);
        expense.setDecidedAt(Instant.now());
        expense.setReason(requireReason(reason));
        return announced(expense);
    }

    /** Entered in error. Kept, with the reason, and no longer counted. */
    @Transactional
    public Expense voidExpense(UUID id, String reason) {
        Expense expense = require(id);
        if (expense.getStatus() == ExpenseStatus.VOIDED
                || expense.getStatus() == ExpenseStatus.REJECTED) {
            throw new Errors.ConflictException(
                    "expense.closed", "This expense no longer counts; there is nothing to void.");
        }
        expense.setStatus(ExpenseStatus.VOIDED);
        expense.setDecidedBy(AuthenticatedUser.currentUserId());
        expense.setDecidedAt(Instant.now());
        expense.setReason(requireReason(reason));
        return announced(expense);
    }

    /**
     * Saved and flushed before the event is written: the version only moves at a flush, and the
     * event carries it as the revision reporting orders by.
     */
    private Expense announced(Expense expense) {
        Expense saved = expenses.saveAndFlush(expense);
        events.expenseChanged(saved);
        return saved;
    }

    private Expense requirePending(UUID id) {
        Expense expense = require(id);
        if (expense.getStatus() != ExpenseStatus.PENDING_APPROVAL) {
            throw new Errors.ConflictException(
                    "expense.not_pending", "This expense is not waiting for approval.");
        }
        return expense;
    }

    private static UUID requireSomeoneElse(Expense expense) {
        UUID approver = AuthenticatedUser.currentUserId();
        if (approver != null && approver.equals(expense.getRecordedBy())) {
            throw new Errors.ForbiddenException(
                    "expense.approver_is_recorder",
                    "Someone other than whoever recorded an expense approves it.");
        }
        return approver;
    }

    private static String requireReason(String reason) {
        if (reason == null || reason.isBlank()) {
            throw new Errors.BadRequestException("expense.reason_required", "Give a reason.");
        }
        return reason.strip();
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
