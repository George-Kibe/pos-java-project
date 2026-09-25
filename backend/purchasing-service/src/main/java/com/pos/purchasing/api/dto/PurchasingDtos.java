package com.pos.purchasing.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;
import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.Digits;
import jakarta.validation.constraints.Email;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotEmpty;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.PositiveOrZero;
import jakarta.validation.constraints.Size;

import com.pos.purchasing.domain.GoodsReceivedNote;
import com.pos.purchasing.domain.GrnLine;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.PurchaseOrderLine;
import com.pos.purchasing.domain.ReorderSuggestion;
import com.pos.purchasing.domain.ReturnReason;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierInvoice;
import com.pos.purchasing.domain.SupplierProduct;
import com.pos.purchasing.domain.SupplierReturn;
import com.pos.purchasing.domain.SupplierStatus;
import com.pos.purchasing.domain.cost.AllocationBasis;
import com.pos.purchasing.domain.matching.LineVariance;
import com.pos.purchasing.domain.matching.MatchResult;

/**
 * The wire shapes.
 *
 * <p>Quantities and money arrive as {@link BigDecimal} and are never widened to a double anywhere
 * on the way in or out. Booleans are boxed with an explicit default, because Jackson 3 rejects a
 * missing primitive and answers "could not be parsed" without naming the field.
 */
public final class PurchasingDtos {

    private PurchasingDtos() {}

    // --- suppliers --------------------------------------------------------------

    public record SupplierRequest(
            @NotBlank @Size(max = 30) String code,
            @NotBlank @Size(max = 200) String name,
            @Size(max = 200) String contactName,
            @Email @Size(max = 320) String email,
            @Size(max = 30) String phone,
            @Size(max = 500) String address,
            @Size(max = 50) String taxIdentifier,
            @PositiveOrZero Integer paymentTermsDays,
            @PositiveOrZero Integer leadTimeDays,
            @Size(max = 3) String currency,
            @Size(max = 1000) String notes) {

        public int paymentTermsOrDefault() {
            return paymentTermsDays == null ? 30 : paymentTermsDays;
        }

        public int leadTimeOrDefault() {
            return leadTimeDays == null ? 7 : leadTimeDays;
        }

        public String currencyOrDefault() {
            return currency == null || currency.isBlank() ? "KES" : currency;
        }
    }

    public record SupplierResponse(
            UUID id,
            String code,
            String name,
            String contactName,
            String email,
            String phone,
            String address,
            String taxIdentifier,
            int paymentTermsDays,
            int leadTimeDays,
            String currency,
            String status,
            String notes) {

        public static SupplierResponse from(Supplier supplier) {
            return new SupplierResponse(
                    supplier.getId(),
                    supplier.getCode(),
                    supplier.getName(),
                    supplier.getContactName(),
                    supplier.getEmail(),
                    supplier.getPhone(),
                    supplier.getAddress(),
                    supplier.getTaxIdentifier(),
                    supplier.getPaymentTermsDays(),
                    supplier.getLeadTimeDays(),
                    supplier.getCurrency(),
                    supplier.getStatus().name(),
                    supplier.getNotes());
        }
    }

    public record SupplierStatusRequest(@NotNull SupplierStatus status) {}

    public record SupplierProductRequest(
            @NotNull UUID productId,
            @Size(max = 50) String sku,
            @Size(max = 200) String productName,
            @Size(max = 50) String supplierSku,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4)
                    BigDecimal agreedUnitCost,
            @DecimalMin(value = "0.0", inclusive = false) @Digits(integer = 16, fraction = 3)
                    BigDecimal minimumOrderQty,
            @PositiveOrZero Integer leadTimeDays,
            Boolean preferred) {

        public boolean isPreferred() {
            return Boolean.TRUE.equals(preferred);
        }
    }

    public record SupplierProductResponse(
            UUID id,
            UUID supplierId,
            String supplierName,
            UUID productId,
            String sku,
            String productName,
            String supplierSku,
            BigDecimal agreedUnitCost,
            BigDecimal lastUnitCost,
            Instant lastReceivedAt,
            BigDecimal minimumOrderQty,
            int effectiveLeadTimeDays,
            boolean preferred,
            String currency) {

        public static SupplierProductResponse from(SupplierProduct product) {
            return new SupplierProductResponse(
                    product.getId(),
                    product.getSupplier().getId(),
                    product.getSupplier().getName(),
                    product.getProductId(),
                    product.getSku(),
                    product.getProductName(),
                    product.getSupplierSku(),
                    product.getAgreedUnitCost(),
                    product.getLastUnitCost(),
                    product.getLastReceivedAt(),
                    product.getMinimumOrderQty(),
                    product.effectiveLeadTimeDays(),
                    product.isPreferred(),
                    product.getCurrency());
        }
    }

    // --- purchase orders --------------------------------------------------------

    public record OrderLineRequest(
            @NotNull UUID productId,
            @Size(max = 50) String sku,
            @Size(max = 200) String productName,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantity,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
            @DecimalMin("0.0") @Digits(integer = 3, fraction = 6) BigDecimal taxRate) {}

    public record PurchaseOrderRequest(
            @NotNull UUID supplierId,
            @NotNull UUID branchId,
            LocalDate expectedDeliveryDate,
            @Size(max = 1000) String notes,
            /** Whether the unit costs carry VAT; stored without it. Omitted, they do not. */
            Boolean costsIncludeTax,
            /** Reorder suggestions this order answers; they are marked ordered with it. */
            List<UUID> fromSuggestions,
            @NotEmpty @Valid List<OrderLineRequest> lines) {

        public boolean includesTax() {
            return Boolean.TRUE.equals(costsIncludeTax);
        }
    }

    public record OrderLinesRequest(
            /** Whether the unit costs carry VAT; stored without it. Omitted, they do not. */
            Boolean costsIncludeTax, @NotEmpty @Valid List<OrderLineRequest> lines) {

        public boolean includesTax() {
            return Boolean.TRUE.equals(costsIncludeTax);
        }
    }

    public record CancellationRequest(@NotBlank @Size(max = 500) String reason) {}

    public record OrderLineResponse(
            UUID id,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityOrdered,
            BigDecimal quantityReceived,
            BigDecimal quantityOutstanding,
            BigDecimal unitCost,
            BigDecimal taxRate,
            BigDecimal taxAmount,
            BigDecimal lineTotal,
            String currency) {

        public static OrderLineResponse from(PurchaseOrderLine line) {
            return new OrderLineResponse(
                    line.getId(),
                    line.getLineNumber(),
                    line.getProductId(),
                    line.getSku(),
                    line.getProductName(),
                    line.getQuantityOrdered(),
                    line.getQuantityReceived(),
                    line.quantityOutstanding(),
                    line.getUnitCost(),
                    line.getTaxRate(),
                    line.getTaxAmount(),
                    line.getLineTotal(),
                    line.getCurrency());
        }
    }

    public record PurchaseOrderResponse(
            UUID id,
            String orderNumber,
            UUID supplierId,
            String supplierName,
            UUID branchId,
            String status,
            LocalDate orderDate,
            LocalDate expectedDeliveryDate,
            BigDecimal netTotal,
            BigDecimal taxTotal,
            BigDecimal grandTotal,
            BigDecimal approvedTotal,
            String currency,
            UUID submittedBy,
            Instant submittedAt,
            UUID approvedBy,
            Instant approvedAt,
            Instant sentAt,
            Instant closedAt,
            Instant cancelledAt,
            String cancellationReason,
            String notes,
            List<OrderLineResponse> lines) {

        public static PurchaseOrderResponse from(PurchaseOrder order) {
            return new PurchaseOrderResponse(
                    order.getId(),
                    order.getOrderNumber(),
                    order.getSupplier().getId(),
                    order.getSupplier().getName(),
                    order.getBranchId(),
                    order.getStatus().name(),
                    order.getOrderDate(),
                    order.getExpectedDeliveryDate(),
                    order.getNetTotal(),
                    order.getTaxTotal(),
                    order.getGrandTotal(),
                    order.getApprovedTotal(),
                    order.getCurrency(),
                    order.getSubmittedBy(),
                    order.getSubmittedAt(),
                    order.getApprovedBy(),
                    order.getApprovedAt(),
                    order.getSentAt(),
                    order.getClosedAt(),
                    order.getCancelledAt(),
                    order.getCancellationReason(),
                    order.getNotes(),
                    order.getLines().stream().map(OrderLineResponse::from).toList());
        }
    }

    // --- goods receipts ---------------------------------------------------------

    public record ReceiptLineRequest(
            @NotNull UUID productId,
            @Size(max = 50) String sku,
            @Size(max = 200) String productName,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantityReceived,
            @DecimalMin("0.0") @Digits(integer = 16, fraction = 3) BigDecimal quantityRejected,
            @Size(max = 500) String rejectionReason,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost,
            @Size(max = 100) String batchNumber,
            LocalDate expiryDate) {}

    public record GoodsReceiptRequest(
            @NotNull UUID supplierId,
            @NotNull UUID branchId,
            UUID purchaseOrderId,
            @Size(max = 50) String deliveryNoteRef,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal freightAmount,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal dutyAmount,
            AllocationBasis allocationBasis,
            @Size(max = 1000) String notes,
            /**
             * Whether the unit costs are as invoiced, with VAT; they are stored without it.
             * Omitted, they are taken to be without VAT, as costs carried over from an order are.
             */
            Boolean costsIncludeTax,
            @NotEmpty @Valid List<ReceiptLineRequest> lines) {

        public boolean includesTax() {
            return Boolean.TRUE.equals(costsIncludeTax);
        }
    }

    public record GrnLineResponse(
            UUID id,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityOrdered,
            BigDecimal quantityReceived,
            BigDecimal quantityRejected,
            BigDecimal quantityAccepted,
            BigDecimal discrepancy,
            String rejectionReason,
            String batchNumber,
            LocalDate expiryDate,
            BigDecimal unitCost,
            BigDecimal landedUnitCost,
            BigDecimal allocatedCharges,
            BigDecimal lineTotal,
            String currency,
            /** The cost as keyed in; {@code unitCost} is always without VAT. */
            BigDecimal enteredUnitCost,
            BigDecimal taxRate,
            BigDecimal inputTax) {

        public static GrnLineResponse from(GrnLine line) {
            return new GrnLineResponse(
                    line.getId(),
                    line.getLineNumber(),
                    line.getProductId(),
                    line.getSku(),
                    line.getProductName(),
                    line.getQuantityOrdered(),
                    line.getQuantityReceived(),
                    line.getQuantityRejected(),
                    line.quantityAccepted(),
                    line.discrepancy(),
                    line.getRejectionReason(),
                    line.getBatchNumber(),
                    line.getExpiryDate(),
                    line.getUnitCost(),
                    line.getLandedUnitCost(),
                    line.getAllocatedCharges(),
                    line.getLineTotal(),
                    line.getCurrency(),
                    line.getEnteredUnitCost(),
                    line.getTaxRate(),
                    line.getInputTax());
        }
    }

    public record GoodsReceiptResponse(
            UUID id,
            String grnNumber,
            UUID supplierId,
            String supplierName,
            UUID purchaseOrderId,
            String purchaseOrderNumber,
            UUID branchId,
            String status,
            String deliveryNoteRef,
            Instant receivedAt,
            UUID receivedBy,
            Instant postedAt,
            UUID postedBy,
            BigDecimal freightAmount,
            BigDecimal dutyAmount,
            String allocationBasis,
            BigDecimal goodsTotal,
            BigDecimal landedTotal,
            String currency,
            String notes,
            List<GrnLineResponse> lines,
            boolean costsIncludeTax,
            BigDecimal inputTaxTotal) {

        public static GoodsReceiptResponse from(GoodsReceivedNote grn) {
            return new GoodsReceiptResponse(
                    grn.getId(),
                    grn.getGrnNumber(),
                    grn.getSupplier().getId(),
                    grn.getSupplier().getName(),
                    grn.getPurchaseOrder() == null ? null : grn.getPurchaseOrder().getId(),
                    grn.getPurchaseOrder() == null ? null : grn.getPurchaseOrder().getOrderNumber(),
                    grn.getBranchId(),
                    grn.getStatus().name(),
                    grn.getDeliveryNoteRef(),
                    grn.getReceivedAt(),
                    grn.getReceivedBy(),
                    grn.getPostedAt(),
                    grn.getPostedBy(),
                    grn.getFreightAmount(),
                    grn.getDutyAmount(),
                    grn.getAllocationBasis().name(),
                    grn.getGoodsTotal(),
                    grn.getLandedTotal(),
                    grn.getCurrency(),
                    grn.getNotes(),
                    grn.getLines().stream().map(GrnLineResponse::from).toList(),
                    grn.isCostsIncludeTax(),
                    grn.getInputTaxTotal());
        }
    }

    // --- supplier invoices ------------------------------------------------------

    public record InvoiceLineRequest(
            @NotNull UUID productId,
            @Size(max = 50) String sku,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantity,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost) {}

    public record SupplierInvoiceRequest(
            @NotNull UUID supplierId,
            @NotBlank @Size(max = 50) String invoiceNumber,
            @NotNull LocalDate invoiceDate,
            @NotNull @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal netAmount,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal taxAmount,
            UUID purchaseOrderId,
            UUID grnId,
            @Valid List<InvoiceLineRequest> lines) {}

    public record ReasonRequest(@NotBlank @Size(max = 500) String reason) {}

    public record VarianceResponse(
            UUID productId,
            String sku,
            String type,
            BigDecimal expected,
            BigDecimal actual,
            BigDecimal difference,
            BigDecimal amountEffect,
            String description) {

        /** A finding as kept with the invoice. */
        public static VarianceResponse from(com.pos.purchasing.domain.InvoiceVariance variance) {
            return new VarianceResponse(
                    variance.getProductId(),
                    variance.getSku(),
                    variance.getType(),
                    variance.getExpected(),
                    variance.getActual(),
                    variance.getDifference(),
                    variance.getAmountEffect(),
                    variance.getDescription());
        }

        public static VarianceResponse from(LineVariance variance) {
            return new VarianceResponse(
                    variance.productId(),
                    variance.sku(),
                    variance.type().name(),
                    variance.expected(),
                    variance.actual(),
                    variance.difference(),
                    variance.amountEffect(),
                    variance.description());
        }
    }

    public record SupplierInvoiceResponse(
            UUID id,
            String invoiceNumber,
            UUID supplierId,
            String supplierName,
            UUID purchaseOrderId,
            UUID grnId,
            UUID branchId,
            LocalDate invoiceDate,
            LocalDate dueDate,
            BigDecimal netAmount,
            BigDecimal taxAmount,
            BigDecimal totalAmount,
            String currency,
            String matchStatus,
            BigDecimal varianceAmount,
            String matchNotes,
            Instant matchedAt,
            UUID matchedBy,
            String overrideReason,
            Instant approvedForPaymentAt,
            UUID approvedForPaymentBy,
            BigDecimal justifiedTotal,
            List<VarianceResponse> variances) {

        public static SupplierInvoiceResponse from(SupplierInvoice invoice, MatchResult result) {
            return new SupplierInvoiceResponse(
                    invoice.getId(),
                    invoice.getInvoiceNumber(),
                    invoice.getSupplier().getId(),
                    invoice.getSupplier().getName(),
                    invoice.getPurchaseOrder() == null ? null : invoice.getPurchaseOrder().getId(),
                    invoice.getGrn() == null ? null : invoice.getGrn().getId(),
                    invoice.getBranchId(),
                    invoice.getInvoiceDate(),
                    invoice.getDueDate(),
                    invoice.getNetAmount(),
                    invoice.getTaxAmount(),
                    invoice.getTotalAmount(),
                    invoice.getCurrency(),
                    invoice.getMatchStatus().name(),
                    invoice.getVarianceAmount(),
                    invoice.getMatchNotes(),
                    invoice.getMatchedAt(),
                    invoice.getMatchedBy(),
                    invoice.getOverrideReason(),
                    invoice.getApprovedForPaymentAt(),
                    invoice.getApprovedForPaymentBy(),
                    result == null ? invoice.getJustifiedTotal() : result.justifiedTotal(),
                    result == null
                            ? invoice.getVariances().stream().map(VarianceResponse::from).toList()
                            : result.variances().stream().map(VarianceResponse::from).toList());
        }

        public static SupplierInvoiceResponse from(SupplierInvoice invoice) {
            return from(invoice, null);
        }
    }

    // --- supplier returns -------------------------------------------------------

    public record ReturnLineRequest(
            @NotNull UUID productId,
            @Size(max = 50) String sku,
            @Size(max = 200) String productName,
            @Size(max = 100) String batchNumber,
            @NotNull
                    @DecimalMin(value = "0.0", inclusive = false)
                    @Digits(integer = 16, fraction = 3)
                    BigDecimal quantity,
            @DecimalMin("0.0") @Digits(integer = 15, fraction = 4) BigDecimal unitCost) {}

    public record SupplierReturnRequest(
            @NotNull UUID supplierId,
            @NotNull UUID branchId,
            UUID grnId,
            @NotNull ReturnReason reasonCode,
            @Size(max = 1000) String notes,
            @NotEmpty @Valid List<ReturnLineRequest> lines) {}

    public record CreditNoteRequest(@NotBlank @Size(max = 50) String creditNoteRef) {}

    public record ReturnLineResponse(
            UUID id,
            int lineNumber,
            UUID productId,
            String sku,
            String productName,
            String batchNumber,
            BigDecimal quantity,
            BigDecimal unitCost,
            BigDecimal lineTotal,
            String currency) {}

    public record SupplierReturnResponse(
            UUID id,
            String returnNumber,
            UUID supplierId,
            String supplierName,
            UUID grnId,
            UUID branchId,
            String status,
            String reasonCode,
            BigDecimal totalAmount,
            String currency,
            Instant sentAt,
            Instant creditedAt,
            String creditNoteRef,
            String notes,
            List<ReturnLineResponse> lines) {

        public static SupplierReturnResponse from(SupplierReturn supplierReturn) {
            return new SupplierReturnResponse(
                    supplierReturn.getId(),
                    supplierReturn.getReturnNumber(),
                    supplierReturn.getSupplier().getId(),
                    supplierReturn.getSupplier().getName(),
                    supplierReturn.getGrn() == null ? null : supplierReturn.getGrn().getId(),
                    supplierReturn.getBranchId(),
                    supplierReturn.getStatus().name(),
                    supplierReturn.getReasonCode().name(),
                    supplierReturn.getTotalAmount(),
                    supplierReturn.getCurrency(),
                    supplierReturn.getSentAt(),
                    supplierReturn.getCreditedAt(),
                    supplierReturn.getCreditNoteRef(),
                    supplierReturn.getNotes(),
                    supplierReturn.getLines().stream()
                            .map(
                                    line ->
                                            new ReturnLineResponse(
                                                    line.getId(),
                                                    line.getLineNumber(),
                                                    line.getProductId(),
                                                    line.getSku(),
                                                    line.getProductName(),
                                                    line.getBatchNumber(),
                                                    line.getQuantity(),
                                                    line.getUnitCost(),
                                                    line.getLineTotal(),
                                                    line.getCurrency()))
                            .toList());
        }
    }

    // --- reorder suggestions ----------------------------------------------------

    public record ReorderSuggestionResponse(
            UUID id,
            UUID productId,
            UUID branchId,
            String sku,
            String productName,
            UUID supplierId,
            String supplierName,
            BigDecimal quantityOnHand,
            BigDecimal reorderPoint,
            BigDecimal suggestedQuantity,
            BigDecimal unitCost,
            BigDecimal estimatedValue,
            String currency,
            String status,
            UUID purchaseOrderId) {

        public static ReorderSuggestionResponse from(ReorderSuggestion suggestion) {
            return new ReorderSuggestionResponse(
                    suggestion.getId(),
                    suggestion.getProductId(),
                    suggestion.getBranchId(),
                    suggestion.getSku(),
                    suggestion.getProductName(),
                    suggestion.getSupplier() == null ? null : suggestion.getSupplier().getId(),
                    suggestion.getSupplier() == null ? null : suggestion.getSupplier().getName(),
                    suggestion.getQuantityOnHand(),
                    suggestion.getReorderPoint(),
                    suggestion.getSuggestedQuantity(),
                    suggestion.getUnitCost(),
                    suggestion.estimatedValue(),
                    suggestion.getCurrency(),
                    suggestion.getStatus().name(),
                    suggestion.getPurchaseOrder() == null
                            ? null
                            : suggestion.getPurchaseOrder().getId());
        }
    }
}
