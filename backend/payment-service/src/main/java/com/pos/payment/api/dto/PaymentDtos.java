package com.pos.payment.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.Pattern;
import jakarta.validation.constraints.Size;

import com.pos.events.payments.PaymentMethod;
import com.pos.payment.domain.MpesaTransaction;
import com.pos.payment.domain.PaymentEvent;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.domain.ReconciliationItem;
import com.pos.payment.domain.ReconciliationRun;
import com.pos.payment.domain.Refund;

public final class PaymentDtos {

    private PaymentDtos() {}

    // --- intents ----------------------------------------------------------------

    public record CaptureRequest(
            @NotBlank @Size(max = 50) @Pattern(regexp = "[A-Za-z0-9-]+") String approvalCode,
            @Size(max = 100) String terminalReference) {}

    public record DeclineRequest(@NotBlank @Size(max = 500) String reason) {}

    public record MpesaStatus(
            String checkoutRequestId,
            String status,
            Integer resultCode,
            String resultDesc,
            String mpesaReceiptNumber,
            String settledBy,
            int queryAttempts) {

        public static MpesaStatus from(MpesaTransaction transaction) {
            return new MpesaStatus(
                    transaction.getCheckoutRequestId(),
                    transaction.getStatus().name(),
                    transaction.getResultCode(),
                    transaction.getResultDesc(),
                    transaction.getMpesaReceiptNumber(),
                    transaction.getSettledBy(),
                    transaction.getQueryAttempts());
        }
    }

    /** No full phone number, ever: masked is all a screen needs. */
    public record IntentResponse(
            UUID id,
            UUID saleId,
            UUID branchId,
            PaymentMethod method,
            BigDecimal amount,
            String currency,
            String status,
            BigDecimal amountAuthorized,
            String providerReference,
            String approvalCode,
            String failureCode,
            String failureMessage,
            String phoneMasked,
            String terminalReference,
            boolean late,
            Instant requestedAt,
            Instant settledAt,
            MpesaStatus mpesa) {

        public static IntentResponse from(PaymentIntent intent) {
            return from(intent, null);
        }

        public static IntentResponse from(PaymentIntent intent, MpesaTransaction transaction) {
            return new IntentResponse(
                    intent.getId(),
                    intent.getSaleId(),
                    intent.getBranchId(),
                    intent.getMethod(),
                    intent.getAmount(),
                    intent.getCurrency(),
                    intent.getStatus().name(),
                    intent.getAmountAuthorized(),
                    intent.getProviderReference(),
                    intent.getApprovalCode(),
                    intent.getFailureCode(),
                    intent.getFailureMessage(),
                    intent.getPhoneMasked(),
                    intent.getTerminalReference(),
                    intent.isLate(),
                    intent.getRequestedAt(),
                    intent.getSettledAt(),
                    transaction == null ? null : MpesaStatus.from(transaction));
        }
    }

    public record EventResponse(String type, String detail, Instant occurredAt) {
        public static EventResponse from(PaymentEvent event) {
            return new EventResponse(event.getType(), event.getDetail(), event.getOccurredAt());
        }
    }

    // --- refunds ----------------------------------------------------------------

    public record RefundCaptureRequest(@NotBlank @Size(max = 100) String terminalReference) {}

    public record SettleRequest(
            @NotBlank @Pattern(regexp = "CASH|BANK_TRANSFER|MPESA_B2C|OTHER") String via,
            @Size(max = 100) String reference,
            @NotBlank @Size(max = 500) String note) {}

    public record RefundResponse(
            UUID id,
            UUID paymentId,
            UUID saleId,
            UUID returnId,
            UUID branchId,
            PaymentMethod method,
            BigDecimal amount,
            String currency,
            String status,
            String actionReason,
            String detail,
            String providerReference,
            String settledVia,
            UUID settledBy,
            String settlementNote,
            Instant requestedAt,
            Instant completedAt) {

        public static RefundResponse from(Refund refund) {
            return new RefundResponse(
                    refund.getId(),
                    refund.getPaymentId(),
                    refund.getSaleId(),
                    refund.getReturnId(),
                    refund.getBranchId(),
                    refund.getMethod(),
                    refund.getAmount(),
                    refund.getCurrency(),
                    refund.getStatus().name(),
                    refund.getActionReason(),
                    refund.getDetail(),
                    refund.getProviderReference(),
                    refund.getSettledVia(),
                    refund.getSettledBy(),
                    refund.getSettlementNote(),
                    refund.getRequestedAt(),
                    refund.getCompletedAt());
        }
    }

    // --- reconciliation ---------------------------------------------------------

    public record ItemResponse(
            String kind,
            String mpesaReceiptNumber,
            UUID paymentId,
            BigDecimal statementAmount,
            BigDecimal recordedAmount,
            BigDecimal difference,
            String note) {

        public static ItemResponse from(ReconciliationItem item) {
            return new ItemResponse(
                    item.getKind(),
                    item.getMpesaReceiptNumber(),
                    item.getPaymentId(),
                    item.getStatementAmount(),
                    item.getRecordedAmount(),
                    item.getDifference(),
                    item.getNote());
        }
    }

    public record RunResponse(
            UUID id,
            LocalDate statementDate,
            String sourceFilename,
            int statementLines,
            int matched,
            int amountMismatches,
            int statementOnly,
            int recordedOnly,
            BigDecimal statementTotal,
            BigDecimal recordedTotal,
            BigDecimal varianceTotal,
            boolean clean,
            UUID runBy,
            Instant createdAt,
            List<ItemResponse> items) {

        public static RunResponse from(ReconciliationRun run, List<ReconciliationItem> items) {
            return new RunResponse(
                    run.getId(),
                    run.getStatementDate(),
                    run.getSourceFilename(),
                    run.getStatementLines(),
                    run.getMatched(),
                    run.getAmountMismatches(),
                    run.getStatementOnly(),
                    run.getRecordedOnly(),
                    run.getStatementTotal(),
                    run.getRecordedTotal(),
                    run.getVarianceTotal(),
                    run.isClean(),
                    run.getRunBy(),
                    run.getCreatedAt(),
                    items == null ? null : items.stream().map(ItemResponse::from).toList());
        }
    }
}
