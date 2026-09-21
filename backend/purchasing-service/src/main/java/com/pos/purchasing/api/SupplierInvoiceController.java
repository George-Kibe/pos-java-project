package com.pos.purchasing.api;

import java.net.URI;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.web.PageResponse;
import com.pos.purchasing.api.dto.PurchasingDtos;
import com.pos.purchasing.domain.InvoiceMatchStatus;
import com.pos.purchasing.service.SupplierInvoiceService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Supplier invoices and the three-way match.
 *
 * <p>Recording an invoice runs the match, so the response says immediately whether it agrees with
 * the order and the delivery - and if not, exactly where it differs and what that is worth.
 */
@RestController
@RequestMapping("/api/v1/supplier-invoices")
@RequiredArgsConstructor
@Tag(name = "Supplier invoices")
public class SupplierInvoiceController {

    private final SupplierInvoiceService invoices;

    @GetMapping
    @PreAuthorize("hasAuthority('supplier-invoice:view')")
    @Operation(summary = "Supplier invoices, optionally by match status")
    public PageResponse<PurchasingDtos.SupplierInvoiceResponse> list(
            @RequestParam(required = false) InvoiceMatchStatus status,
            @PageableDefault(size = 50) Pageable pageable) {

        return PageResponse.of(
                invoices.list(status, pageable), PurchasingDtos.SupplierInvoiceResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('supplier-invoice:view')")
    @Operation(summary = "One supplier invoice")
    public PurchasingDtos.SupplierInvoiceResponse get(@PathVariable UUID id) {
        return PurchasingDtos.SupplierInvoiceResponse.from(invoices.require(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('supplier-invoice:manage')")
    @Operation(summary = "Record an invoice and match it against the order and the delivery")
    public ResponseEntity<PurchasingDtos.SupplierInvoiceResponse> record(
            @Valid @RequestBody PurchasingDtos.SupplierInvoiceRequest request) {

        SupplierInvoiceService.MatchedInvoice matched =
                invoices.record(
                        request.supplierId(),
                        request.invoiceNumber(),
                        request.invoiceDate(),
                        request.netAmount(),
                        request.taxAmount(),
                        request.purchaseOrderId(),
                        request.grnId(),
                        request.lines() == null
                                ? java.util.List.of()
                                : request.lines().stream()
                                        .map(
                                                line ->
                                                        new SupplierInvoiceService
                                                                .InvoiceLineRequest(
                                                                line.productId(),
                                                                line.sku(),
                                                                line.quantity(),
                                                                line.unitCost()))
                                        .toList());

        return ResponseEntity.created(
                        URI.create("/api/v1/supplier-invoices/" + matched.invoice().getId()))
                .body(
                        PurchasingDtos.SupplierInvoiceResponse.from(
                                matched.invoice(), matched.result()));
    }

    @PostMapping("/{id}/accept-exception")
    @PreAuthorize("hasAuthority('supplier-invoice:manage')")
    @Operation(summary = "Knowingly accept a match exception, with a reason")
    public PurchasingDtos.SupplierInvoiceResponse acceptException(
            @PathVariable UUID id, @Valid @RequestBody PurchasingDtos.ReasonRequest request) {
        return PurchasingDtos.SupplierInvoiceResponse.from(
                invoices.acceptException(id, request.reason()));
    }

    @PostMapping("/{id}/dispute")
    @PreAuthorize("hasAuthority('supplier-invoice:manage')")
    @Operation(summary = "Take an invoice up with the supplier")
    public PurchasingDtos.SupplierInvoiceResponse dispute(
            @PathVariable UUID id, @Valid @RequestBody PurchasingDtos.ReasonRequest request) {
        return PurchasingDtos.SupplierInvoiceResponse.from(invoices.dispute(id, request.reason()));
    }

    @PostMapping("/{id}/approve-for-payment")
    @PreAuthorize("hasAuthority('supplier-invoice:manage')")
    @Operation(summary = "Clear a matched invoice for payment")
    public PurchasingDtos.SupplierInvoiceResponse approveForPayment(@PathVariable UUID id) {
        return PurchasingDtos.SupplierInvoiceResponse.from(invoices.approveForPayment(id));
    }
}
