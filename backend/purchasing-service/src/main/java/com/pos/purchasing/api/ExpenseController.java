package com.pos.purchasing.api;

import java.net.URI;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
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

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;
import com.pos.purchasing.api.dto.ExpenseDtos;
import com.pos.purchasing.domain.Expense;
import com.pos.purchasing.domain.ExpenseStatus;
import com.pos.purchasing.service.ExpenseService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The expenses register. A branch's expenses belong to whoever works there; head office's ({@code
 * branchId} omitted) need {@code expense:head-office}, which only the administrator holds.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Expenses")
public class ExpenseController {

    static final String HEAD_OFFICE = "expense:head-office";

    private final ExpenseService expenses;
    private final BranchAccessGuard branchAccess;

    @PostMapping("/expenses")
    @PreAuthorize("hasAuthority('expense:record')")
    @Operation(
            summary = "Record an expense",
            description =
                    "Counts at once up to the approval limit; above it, only once someone other"
                            + " than the recorder approves it.")
    public ResponseEntity<ExpenseDtos.ExpenseResponse> record(
            @Valid @RequestBody ExpenseDtos.ExpenseRequest request) {
        requireScope(request.branchId());
        Expense expense =
                expenses.record(
                        new ExpenseService.NewExpense(
                                request.branchId(),
                                request.category(),
                                request.description(),
                                request.payee(),
                                request.reference(),
                                request.incurredOn(),
                                request.amount(),
                                request.taxAmount()));
        return ResponseEntity.created(URI.create("/api/v1/expenses/" + expense.getId()))
                .body(ExpenseDtos.ExpenseResponse.from(expense));
    }

    @GetMapping("/expenses")
    @PreAuthorize("hasAnyAuthority('expense:view', 'expense:record', 'expense:approve')")
    @Operation(summary = "A branch's expenses in a period, or head office's without a branch")
    public PageResponse<ExpenseDtos.ExpenseResponse> list(
            @RequestParam(required = false) UUID branchId,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate from,
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate to,
            @RequestParam(required = false) ExpenseStatus status,
            @PageableDefault(
                            size = 50,
                            sort = {"incurredOn", "createdAt"},
                            direction = org.springframework.data.domain.Sort.Direction.DESC)
                    Pageable pageable) {
        requireScope(branchId);
        return PageResponse.of(
                expenses.list(branchId, from, to, status, pageable),
                ExpenseDtos.ExpenseResponse::from);
    }

    @GetMapping("/expenses/{id}")
    @PreAuthorize("hasAnyAuthority('expense:view', 'expense:record', 'expense:approve')")
    @Operation(summary = "One expense")
    public ExpenseDtos.ExpenseResponse get(@PathVariable UUID id) {
        Expense expense = expenses.require(id);
        requireScope(expense.getBranchId());
        return ExpenseDtos.ExpenseResponse.from(expense);
    }

    @PostMapping("/expenses/{id}/approve")
    @PreAuthorize("hasAuthority('expense:approve')")
    @Operation(summary = "Approve an expense above the limit; not by whoever recorded it")
    public ExpenseDtos.ExpenseResponse approve(@PathVariable UUID id) {
        requireScope(expenses.require(id).getBranchId());
        return ExpenseDtos.ExpenseResponse.from(expenses.approve(id));
    }

    @PostMapping("/expenses/{id}/reject")
    @PreAuthorize("hasAuthority('expense:approve')")
    @Operation(summary = "Refuse an expense above the limit, for a reason")
    public ExpenseDtos.ExpenseResponse reject(
            @PathVariable UUID id, @Valid @RequestBody ExpenseDtos.ReasonRequest request) {
        requireScope(expenses.require(id).getBranchId());
        return ExpenseDtos.ExpenseResponse.from(expenses.reject(id, request.reason()));
    }

    @PostMapping("/expenses/{id}/void")
    @PreAuthorize("hasAuthority('expense:record')")
    @Operation(summary = "Void an expense entered in error, for a reason; it is kept")
    public ExpenseDtos.ExpenseResponse voidExpense(
            @PathVariable UUID id, @Valid @RequestBody ExpenseDtos.ReasonRequest request) {
        requireScope(expenses.require(id).getBranchId());
        return ExpenseDtos.ExpenseResponse.from(expenses.voidExpense(id, request.reason()));
    }

    @GetMapping("/expense-settings")
    @PreAuthorize(
            "hasAnyAuthority('expense:view', 'expense:record', 'expense:approve', 'settings:manage')")
    @Operation(summary = "The amount above which an expense needs a second person's approval")
    public ExpenseDtos.ExpenseSettingsResponse settings() {
        return ExpenseDtos.ExpenseSettingsResponse.from(expenses.settings());
    }

    @PutMapping("/expense-settings")
    @PreAuthorize("hasAuthority('settings:manage')")
    @Operation(summary = "Change the approval limit")
    public ExpenseDtos.ExpenseSettingsResponse setApprovalLimit(
            @Valid @RequestBody ExpenseDtos.ApprovalLimitRequest request) {
        return ExpenseDtos.ExpenseSettingsResponse.from(
                expenses.setApprovalLimit(request.approvalLimit()));
    }

    /** A branch's to those assigned there; head office's to the holder of expense:head-office. */
    private void requireScope(UUID branchId) {
        if (branchId != null) {
            branchAccess.requireAccess(branchId);
        } else if (!AuthenticatedUser.require().permissions().contains(HEAD_OFFICE)) {
            throw new Errors.ForbiddenException(
                    "expense.head_office",
                    "Head office's expenses are the administrator's to see and record.");
        }
    }
}
