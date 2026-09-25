package com.pos.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.pos.purchasing.domain.Expense;
import com.pos.purchasing.domain.ExpenseCategory;
import com.pos.purchasing.domain.ExpenseSettings;

public final class ExpenseDtos {

    private ExpenseDtos() {}

    /**
     * @param branchId the branch it was spent for; omitted for head office, which only the
     *     administrator records
     * @param amount without VAT
     * @param taxAmount the VAT on it, if any
     */
    public record ExpenseRequest(
            UUID branchId,
            @NotNull ExpenseCategory category,
            @NotBlank @Size(max = 300) String description,
            @Size(max = 200) String payee,
            @Size(max = 100) String reference,
            @NotNull LocalDate incurredOn,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 15, fraction = 4)
                    BigDecimal amount,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal taxAmount) {}

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    public record ApprovalLimitRequest(
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4)
                    BigDecimal approvalLimit) {}

    public record ExpenseSettingsResponse(BigDecimal approvalLimit, String currency) {

        public static ExpenseSettingsResponse from(ExpenseSettings settings) {
            return new ExpenseSettingsResponse(settings.getApprovalLimit(), settings.getCurrency());
        }
    }

    public record ExpenseResponse(
            UUID id,
            String expenseNumber,
            UUID branchId,
            String category,
            String description,
            String payee,
            String reference,
            LocalDate incurredOn,
            BigDecimal amount,
            BigDecimal taxAmount,
            String currency,
            String status,
            boolean needsApproval,
            UUID recordedBy,
            Instant recordedAt,
            UUID decidedBy,
            Instant decidedAt,
            String reason) {

        public static ExpenseResponse from(Expense expense) {
            return new ExpenseResponse(
                    expense.getId(),
                    expense.getExpenseNumber(),
                    expense.getBranchId(),
                    expense.getCategory().name(),
                    expense.getDescription(),
                    expense.getPayee(),
                    expense.getReference(),
                    expense.getIncurredOn(),
                    expense.getAmount(),
                    expense.getTaxAmount(),
                    expense.getCurrency(),
                    expense.getStatus().name(),
                    expense.isNeedsApproval(),
                    expense.getRecordedBy(),
                    expense.getRecordedAt(),
                    expense.getDecidedBy(),
                    expense.getDecidedAt(),
                    expense.getReason());
        }
    }
}
