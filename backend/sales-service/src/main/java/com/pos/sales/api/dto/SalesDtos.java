package com.pos.sales.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Size;

import com.pos.events.EventJson;
import com.pos.events.payments.PaymentMethod;
import com.pos.sales.domain.Cart;
import com.pos.sales.domain.CartLine;
import com.pos.sales.domain.Receipt;
import com.pos.sales.domain.ReturnReason;
import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleLine;
import com.pos.sales.domain.SalePayment;
import com.pos.sales.domain.SaleReturn;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.policy.ChangeCalculator;
import com.pos.sales.domain.policy.ChangeDue;
import com.pos.sales.domain.policy.ReturnEligibility;
import com.pos.sales.domain.totals.TaxClassTotal;

/**
 * The wire shapes.
 *
 * <p>Booleans in requests are boxed with explicit defaults: Jackson 3 rejects a missing primitive
 * and answers without naming the field. Money and quantities are {@link BigDecimal} end to end.
 */
public final class SalesDtos {

    private SalesDtos() {}

    // --- till sessions ----------------------------------------------------------

    public record OpenSessionRequest(
            @NotNull UUID branchId,
            @NotNull UUID registerId,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4)
                    BigDecimal openingFloat) {}

    public record CashMovementRequest(
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 15, fraction = 4)
                    BigDecimal amount,
            @NotBlank @Size(max = 500) String reason,
            @Size(max = 100) String reference) {}

    public record CloseSessionRequest(
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal countedCash,
            @Size(max = 1000) String notes) {}

    public record TillSessionResponse(
            UUID id,
            UUID branchId,
            UUID registerId,
            UUID cashierId,
            String status,
            Instant openedAt,
            Instant closedAt,
            BigDecimal openingFloat,
            BigDecimal cashSales,
            BigDecimal cashRefunds,
            BigDecimal cashDrops,
            BigDecimal nonCashSales,
            BigDecimal expectedCash,
            BigDecimal countedCash,
            BigDecimal variance,
            int saleCount,
            String currency) {

        public static TillSessionResponse from(TillSession session) {
            return new TillSessionResponse(
                    session.getId(),
                    session.getBranchId(),
                    session.getRegisterId(),
                    session.getCashierId(),
                    session.getStatus().name(),
                    session.getOpenedAt(),
                    session.getClosedAt(),
                    session.getOpeningFloat(),
                    session.getCashSales(),
                    session.getCashRefunds(),
                    session.getCashDrops(),
                    session.getNonCashSales(),
                    // Live while the shift is open, so a supervisor can see mid-shift what the
                    // drawer should hold.
                    session.getExpectedCash() != null
                            ? session.getExpectedCash()
                            : session.reconcile(null).expectedCash(),
                    session.getCountedCash(),
                    session.getVariance(),
                    session.getSaleCount(),
                    session.getCurrency());
        }
    }

    // --- carts ------------------------------------------------------------------

    public record OpenCartRequest(@NotNull UUID tillSessionId, UUID customerId, Boolean member) {
        public boolean isMember() {
            return Boolean.TRUE.equals(member);
        }
    }

    public record AddLineRequest(
            @NotNull UUID productId,
            @Size(max = 50) String sku,
            @Size(max = 50) String barcode,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantity,
            Boolean weighed) {
        public boolean isWeighed() {
            return Boolean.TRUE.equals(weighed);
        }
    }

    public record QuantityRequest(
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantity) {}

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    public record PriceOverrideRequest(
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal unitPrice,
            @NotBlank @Size(max = 500) String reason) {}

    public record RecallRequest(@NotNull UUID branchId, @NotBlank @Size(max = 12) String code) {}

    public record CustomerRequest(UUID customerId, Boolean member) {
        public boolean isMember() {
            return Boolean.TRUE.equals(member);
        }
    }

    public record CartLineResponse(
            UUID id,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            String barcode,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String priceSource,
            boolean taxInclusive,
            String taxClassCode,
            BigDecimal taxRate,
            BigDecimal subtotal,
            BigDecimal discountTotal,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal lineTotal,
            BigDecimal originalUnitPrice,
            String overrideReason,
            boolean voided,
            String voidReason) {

        public static CartLineResponse from(CartLine line) {
            return new CartLineResponse(
                    line.getId(),
                    line.getLineNumber(),
                    line.getProductId(),
                    line.getSku(),
                    line.getProductName(),
                    line.getBarcode(),
                    line.getQuantity(),
                    line.getUnitPrice(),
                    line.getPriceSource().name(),
                    line.isTaxInclusive(),
                    line.getTaxClassCode(),
                    line.getTaxRate(),
                    line.getSubtotal(),
                    line.getDiscountTotal(),
                    line.getNetAmount(),
                    line.getTaxAmount(),
                    line.getLineTotal(),
                    line.getOriginalUnitPrice(),
                    line.getOverrideReason(),
                    line.isVoided(),
                    line.getVoidReason());
        }
    }

    public record CartResponse(
            UUID id,
            UUID tillSessionId,
            UUID branchId,
            UUID customerId,
            boolean member,
            String status,
            String suspendCode,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal discountTotal,
            BigDecimal grandTotal,
            String currency,
            List<CartLineResponse> lines) {

        public static CartResponse from(Cart cart) {
            return new CartResponse(
                    cart.getId(),
                    cart.getTillSession() == null ? null : cart.getTillSession().getId(),
                    cart.getBranchId(),
                    cart.getCustomerId(),
                    cart.isMember(),
                    cart.getStatus().name(),
                    cart.getSuspendCode(),
                    cart.getNetTotal(),
                    cart.getTaxTotal(),
                    cart.getDiscountTotal(),
                    cart.getGrandTotal(),
                    cart.getCurrency(),
                    cart.getLines().stream().map(CartLineResponse::from).toList());
        }
    }

    // --- sales ------------------------------------------------------------------

    public record CheckoutRequest(
            @NotNull UUID cartId,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal clientGrandTotal) {}

    public record TenderLine(
            @NotNull PaymentMethod method,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 15, fraction = 4)
                    BigDecimal amount,
            @Size(max = 20) String phoneNumber,
            @Size(max = 100) String terminalReference) {}

    public record TenderRequest(
            @NotEmpty @Valid List<TenderLine> tenders,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal amountTendered) {}

    public record SaleLineResponse(
            UUID id,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            BigDecimal quantityReturned,
            BigDecimal unitPrice,
            String priceSource,
            String taxClassCode,
            BigDecimal taxRate,
            BigDecimal discountTotal,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal lineTotal) {

        public static SaleLineResponse from(SaleLine line) {
            return new SaleLineResponse(
                    line.getId(),
                    line.getLineNumber(),
                    line.getProductId(),
                    line.getSku(),
                    line.getProductName(),
                    line.getQuantity(),
                    line.getQuantityReturned(),
                    line.getUnitPrice(),
                    line.getPriceSource().name(),
                    line.getTaxClassCode(),
                    line.getTaxRate(),
                    line.getDiscountTotal(),
                    line.getNetAmount(),
                    line.getTaxAmount(),
                    line.getLineTotal());
        }
    }

    /** Never carries the phone number: only the masked form ever leaves this service. */
    public record PaymentResponse(
            UUID paymentIntentId,
            String method,
            String status,
            BigDecimal amount,
            BigDecimal amountAuthorized,
            String providerReference,
            String approvalCode,
            String terminalReference,
            String phoneNumberMasked,
            String failureReason) {

        public static PaymentResponse from(SalePayment payment) {
            return new PaymentResponse(
                    payment.getPaymentIntentId(),
                    payment.getMethod().name(),
                    payment.getStatus().name(),
                    payment.getAmount(),
                    payment.getAmountAuthorized(),
                    payment.getProviderReference(),
                    payment.getApprovalCode(),
                    payment.getTerminalReference(),
                    payment.getPhoneNumberMasked(),
                    payment.getFailureReason());
        }
    }

    public record ChangeResponse(
            BigDecimal amount,
            List<ChangeDue.DenominationCount> denominations,
            BigDecimal unpayableRemainder) {}

    public record SaleResponse(
            UUID id,
            String receiptNumber,
            UUID clientSaleId,
            UUID branchId,
            UUID registerId,
            UUID tillSessionId,
            UUID cashierId,
            UUID customerId,
            String status,
            String origin,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal discountTotal,
            BigDecimal grandTotal,
            BigDecimal outstanding,
            BigDecimal amountTendered,
            BigDecimal changeGiven,
            ChangeResponse change,
            String currency,
            BigDecimal clientGrandTotal,
            boolean priceVarianceFlagged,
            BigDecimal priceVarianceAmount,
            Instant occurredAt,
            Instant completedAt,
            String cancellationReason,
            Instant voidedAt,
            String voidReason,
            List<SaleLineResponse> lines,
            List<PaymentResponse> payments) {

        public static SaleResponse from(Sale sale) {
            ChangeResponse change = null;
            if (sale.getAmountTendered() != null
                    && sale.getAmountTendered().compareTo(sale.getGrandTotal()) >= 0) {
                ChangeDue due =
                        ChangeCalculator.calculate(sale.getGrandTotal(), sale.getAmountTendered());
                change =
                        new ChangeResponse(
                                due.amount(), due.denominations(), due.unpayableRemainder());
            }
            return new SaleResponse(
                    sale.getId(),
                    sale.getReceiptNumber(),
                    sale.getClientSaleId(),
                    sale.getBranchId(),
                    sale.getRegisterId(),
                    sale.getTillSession() == null ? null : sale.getTillSession().getId(),
                    sale.getCashierId(),
                    sale.getCustomerId(),
                    sale.getStatus().name(),
                    sale.getOrigin().name(),
                    sale.getNetTotal(),
                    sale.getTaxTotal(),
                    sale.getDiscountTotal(),
                    sale.getGrandTotal(),
                    sale.outstanding(),
                    sale.getAmountTendered(),
                    sale.getChangeGiven(),
                    change,
                    sale.getCurrency(),
                    sale.getClientGrandTotal(),
                    sale.isPriceVarianceFlagged(),
                    sale.getPriceVarianceAmount(),
                    sale.getOccurredAt(),
                    sale.getCompletedAt(),
                    sale.getCancellationReason(),
                    sale.getVoidedAt(),
                    sale.getVoidReason(),
                    sale.getLines().stream().map(SaleLineResponse::from).toList(),
                    sale.getPayments().stream().map(PaymentResponse::from).toList());
        }
    }

    public record ReceiptResponse(
            UUID id,
            UUID saleId,
            String receiptNumber,
            String type,
            Instant issuedAt,
            List<TaxClassTotal> taxBreakdown,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal grandTotal,
            String currency,
            int printCount) {

        public static ReceiptResponse from(Receipt receipt) {
            TaxClassTotal[] breakdown =
                    EventJson.read(receipt.getTaxBreakdown(), TaxClassTotal[].class);
            return new ReceiptResponse(
                    receipt.getId(),
                    receipt.getSale().getId(),
                    receipt.getReceiptNumber(),
                    receipt.getType().name(),
                    receipt.getIssuedAt(),
                    breakdown == null ? List.of() : List.of(breakdown),
                    receipt.getNetTotal(),
                    receipt.getTaxTotal(),
                    receipt.getGrandTotal(),
                    receipt.getCurrency(),
                    receipt.getPrintCount());
        }
    }

    // --- returns ----------------------------------------------------------------

    public record ReturnLineRequest(
            @NotNull UUID saleLineId,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantity,
            /** Required, not defaulted: the cashier has to decide, with the goods in hand. */
            @NotNull Boolean resaleable,
            @Size(max = 500) String conditionNote) {}

    public record ReturnRequest(
            @NotNull UUID saleId,
            @NotNull ReturnReason reason,
            PaymentMethod refundMethod,
            @Size(max = 1000) String notes,
            /** Only needed outside the returns window; the approver is the caller. */
            @Size(max = 500) String policyOverrideReason,
            @NotEmpty @Valid List<ReturnLineRequest> lines) {}

    public record ReturnLineResponse(
            UUID id,
            UUID saleLineId,
            UUID productId,
            String sku,
            BigDecimal quantity,
            BigDecimal refundAmount,
            BigDecimal taxAmount,
            boolean resaleable) {}

    public record ReturnResponse(
            UUID id,
            String returnNumber,
            UUID originalSaleId,
            String originalReceiptNumber,
            UUID branchId,
            String status,
            String reason,
            String refundMethod,
            BigDecimal refundTotal,
            BigDecimal taxRefunded,
            Integer daysSinceSale,
            boolean outsidePolicyWindow,
            UUID policyOverrideBy,
            String policyOverrideReason,
            Instant completedAt,
            String currency,
            List<ReturnLineResponse> lines) {

        public static ReturnResponse from(SaleReturn saleReturn) {
            return new ReturnResponse(
                    saleReturn.getId(),
                    saleReturn.getReturnNumber(),
                    saleReturn.getOriginalSale().getId(),
                    saleReturn.getOriginalSale().getReceiptNumber(),
                    saleReturn.getBranchId(),
                    saleReturn.getStatus().name(),
                    saleReturn.getReasonCode().name(),
                    saleReturn.getRefundMethod().name(),
                    saleReturn.getRefundTotal(),
                    saleReturn.getTaxRefunded(),
                    saleReturn.getDaysSinceSale(),
                    saleReturn.isOutsidePolicyWindow(),
                    saleReturn.getPolicyOverrideBy(),
                    saleReturn.getPolicyOverrideReason(),
                    saleReturn.getCompletedAt(),
                    saleReturn.getCurrency(),
                    saleReturn.getLines().stream()
                            .map(
                                    line ->
                                            new ReturnLineResponse(
                                                    line.getId(),
                                                    line.getSaleLine().getId(),
                                                    line.getProductId(),
                                                    line.getSku(),
                                                    line.getQuantity(),
                                                    line.getRefundAmount(),
                                                    line.getTaxAmount(),
                                                    line.isResaleable()))
                            .toList());
        }
    }

    public record EligibilityResponse(
            UUID saleLineId,
            boolean eligible,
            BigDecimal maximumReturnable,
            BigDecimal alreadyReturned,
            boolean requiresOverride,
            String reason) {

        public static EligibilityResponse from(ReturnEligibility eligibility) {
            return new EligibilityResponse(
                    eligibility.saleLineId(),
                    eligibility.eligible(),
                    eligibility.maximumReturnable(),
                    eligibility.alreadyReturned(),
                    eligibility.requiresOverride(),
                    eligibility.reason());
        }
    }

    // --- offline sync -----------------------------------------------------------

    public record OfflineLineRequest(
            @NotNull UUID productId,
            @Size(max = 50) String sku,
            @Size(max = 50) String barcode,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantity,
            @DecimalMin("0.0") BigDecimal unitPrice,
            @DecimalMin("0.0") BigDecimal lineTotal) {}

    public record OfflineSaleRequest(
            @NotNull UUID clientSaleId,
            UUID tillSessionId,
            UUID customerId,
            Boolean member,
            Instant occurredAt,
            PaymentMethod paymentMethod,
            @DecimalMin("0.0") BigDecimal amountTendered,
            @DecimalMin("0.0") BigDecimal claimedGrandTotal,
            @NotEmpty @Valid List<OfflineLineRequest> lines) {

        public boolean isMember() {
            return Boolean.TRUE.equals(member);
        }
    }

    public record SyncRequest(
            @NotNull UUID branchId,
            UUID registerId,
            @NotNull @Size(max = 500) @Valid List<OfflineSaleRequest> sales) {}
}
