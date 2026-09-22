package com.pos.sales.api;

import java.net.URI;
import java.util.List;
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
import org.springframework.web.bind.annotation.RequestHeader;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;
import com.pos.messaging.idempotency.IdempotencyFilter;
import com.pos.sales.api.dto.SalesDtos;
import com.pos.sales.domain.Sale;
import com.pos.sales.service.CartService;
import com.pos.sales.service.CheckoutService;
import com.pos.sales.service.OfflineSyncService;
import com.pos.sales.service.ReceiptService;
import com.pos.sales.service.SaleQueryService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Checkout, payment, voids, receipts and offline sync. */
@RestController
@RequestMapping("/api/v1/sales")
@RequiredArgsConstructor
@Tag(name = "Sales")
public class SaleController {

    private final CheckoutService checkout;
    private final CartService carts;
    private final SaleQueryService query;
    private final ReceiptService receipts;
    private final OfflineSyncService offlineSync;
    private final BranchAccessGuard branchAccess;

    @PostMapping("/checkout")
    @PreAuthorize("hasAuthority('sale:create')")
    @Operation(summary = "Turn a basket into a sale, with totals revalidated against catalog")
    public ResponseEntity<SalesDtos.SaleResponse> checkout(
            @Valid @RequestBody SalesDtos.CheckoutRequest request) {

        // Before the sale exists, not after: a 403 must not leave a sale behind it.
        branchAccess.requireAccess(carts.require(request.cartId()).getBranchId());
        Sale sale = checkout.checkout(request.cartId(), request.clientGrandTotal(), bearerToken());
        return ResponseEntity.created(URI.create("/api/v1/sales/" + sale.getId()))
                .body(SalesDtos.SaleResponse.from(sale));
    }

    @PostMapping("/{id}/tender")
    @PreAuthorize("hasAuthority('payment:take')")
    @Operation(
            summary =
                    "Take payment: cash completes at once, a provider tender waits for the answer")
    public SalesDtos.SaleResponse tender(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.TenderRequest request) {
        branchAccess.requireAccess(checkout.require(id).getBranchId());
        return SalesDtos.SaleResponse.from(
                checkout.tender(
                        id,
                        request.tenders().stream()
                                .map(
                                        line ->
                                                new CheckoutService.Tender(
                                                        line.method(),
                                                        line.amount(),
                                                        line.phoneNumber(),
                                                        line.terminalReference()))
                                .toList(),
                        request.amountTendered()));
    }

    @PostMapping("/{id}/cancel")
    @PreAuthorize("hasAuthority('sale:create')")
    @Operation(summary = "Abandon an unpaid sale and release its stock")
    public SalesDtos.SaleResponse cancel(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.ReasonRequest request) {
        branchAccess.requireAccess(checkout.require(id).getBranchId());
        return SalesDtos.SaleResponse.from(checkout.cancel(id, request.reason()));
    }

    @PostMapping("/{id}/void")
    @PreAuthorize("hasAuthority('sale:void')")
    @Operation(summary = "Reverse a paid sale; the approver is the caller, and it is audited")
    public SalesDtos.SaleResponse voidSale(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.ReasonRequest request) {
        branchAccess.requireAccess(checkout.require(id).getBranchId());
        return SalesDtos.SaleResponse.from(
                checkout.voidSale(id, request.reason(), AuthenticatedUser.require().userId()));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('sale:create', 'report:view:branch')")
    @Operation(summary = "One sale")
    public SalesDtos.SaleResponse get(@PathVariable UUID id) {
        Sale sale = checkout.require(id);
        branchAccess.requireAccess(sale.getBranchId());
        return SalesDtos.SaleResponse.from(sale);
    }

    @GetMapping
    @PreAuthorize("hasAnyAuthority('sale:create', 'report:view:branch')")
    @Operation(summary = "Sales at a branch, most recent first")
    public PageResponse<SalesDtos.SaleResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(query.atBranch(branchId, pageable), SalesDtos.SaleResponse::from);
    }

    @GetMapping("/receipt/{receiptNumber}")
    @PreAuthorize("hasAnyAuthority('sale:create', 'report:view:branch')")
    @Operation(summary = "Look a sale up by the number printed on its receipt")
    public SalesDtos.SaleResponse byReceipt(@PathVariable String receiptNumber) {
        Sale sale = query.byReceiptNumber(receiptNumber);
        branchAccess.requireAccess(sale.getBranchId());
        return SalesDtos.SaleResponse.from(sale);
    }

    @GetMapping("/{id}/receipts")
    @PreAuthorize("hasAnyAuthority('sale:create', 'report:view:branch')")
    @Operation(summary = "The receipts issued for a sale, with the tax breakdown per class")
    public List<SalesDtos.ReceiptResponse> receipts(@PathVariable UUID id) {
        branchAccess.requireAccess(checkout.require(id).getBranchId());
        return receipts.forSale(id).stream().map(SalesDtos.ReceiptResponse::from).toList();
    }

    @PostMapping("/receipts/{receiptId}/reprint")
    @PreAuthorize("hasAuthority('sale:create')")
    @Operation(summary = "Record a reprint; counted, because reprints are how goods walk out twice")
    public SalesDtos.ReceiptResponse reprint(@PathVariable UUID receiptId) {
        var receipt = receipts.require(receiptId);
        branchAccess.requireAccess(receipt.getBranchId());
        return SalesDtos.ReceiptResponse.from(receipts.recordReprint(receiptId));
    }

    @PostMapping("/sync")
    @PreAuthorize("hasAuthority('sale:create')")
    @Operation(
            summary =
                    "Replay sales a terminal took offline; duplicates are reported, never"
                            + " recreated")
    public OfflineSyncService.BatchResult sync(
            @RequestHeader(value = IdempotencyFilter.HEADER, required = false)
                    String idempotencyKey,
            @Valid @RequestBody SalesDtos.SyncRequest request) {

        branchAccess.requireAccess(request.branchId());
        return offlineSync.sync(
                idempotencyKey,
                request.branchId(),
                request.registerId(),
                request.sales().stream()
                        .map(
                                sale ->
                                        new OfflineSyncService.OfflineSale(
                                                sale.clientSaleId(),
                                                request.registerId(),
                                                sale.tillSessionId(),
                                                sale.customerId(),
                                                sale.isMember(),
                                                sale.occurredAt(),
                                                sale.paymentMethod(),
                                                sale.amountTendered(),
                                                sale.claimedGrandTotal(),
                                                sale.lines().stream()
                                                        .map(
                                                                line ->
                                                                        new OfflineSyncService
                                                                                .OfflineLine(
                                                                                line.productId(),
                                                                                line.sku(),
                                                                                line.barcode(),
                                                                                line.quantity(),
                                                                                line.unitPrice(),
                                                                                line.lineTotal()))
                                                        .toList()))
                        .toList(),
                bearerToken());
    }

    /** The caller's verified token, forwarded to catalog and inventory on their behalf. */
    private static String bearerToken() {
        return AuthenticatedUser.bearerToken().orElse(null);
    }
}
