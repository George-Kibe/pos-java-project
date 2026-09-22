package com.pos.payment.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;
import com.pos.payment.api.dto.PaymentDtos;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.service.MpesaTransactionService;
import com.pos.payment.service.PaymentIntentService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/payments")
@RequiredArgsConstructor
@Tag(name = "Payments")
public class PaymentController {

    private final PaymentIntentService intents;
    private final MpesaTransactionService mpesa;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAnyAuthority('payment:take', 'payment:reconcile', 'report:view:branch')")
    @Operation(summary = "Payments requested at a branch, most recent first")
    public PageResponse<PaymentDtos.IntentResponse> list(
            @RequestParam UUID branchId, @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(intents.list(branchId, pageable), PaymentDtos.IntentResponse::from);
    }

    @GetMapping("/sales/{saleId}")
    @PreAuthorize("hasAnyAuthority('payment:take', 'payment:reconcile', 'report:view:branch')")
    @Operation(summary = "Every tender on one sale")
    public List<PaymentDtos.IntentResponse> forSale(@PathVariable UUID saleId) {
        List<PaymentIntent> found = intents.forSale(saleId);
        found.forEach(intent -> branchAccess.requireAccess(intent.getBranchId()));
        return found.stream().map(PaymentDtos.IntentResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAnyAuthority('payment:take', 'payment:reconcile', 'report:view:branch')")
    @Operation(summary = "One payment, with the M-Pesa transaction behind it if there is one")
    public PaymentDtos.IntentResponse get(@PathVariable UUID id) {
        PaymentIntent intent = scoped(id);
        return PaymentDtos.IntentResponse.from(intent, mpesa.latestFor(id).orElse(null));
    }

    @GetMapping("/{id}/events")
    @PreAuthorize("hasAnyAuthority('payment:take', 'payment:reconcile', 'report:view:branch')")
    @Operation(summary = "What happened to a payment, in order")
    public List<PaymentDtos.EventResponse> history(@PathVariable UUID id) {
        scoped(id);
        return intents.historyOf(id).stream().map(PaymentDtos.EventResponse::from).toList();
    }

    @PostMapping("/{id}/capture")
    @PreAuthorize("hasAuthority('payment:take')")
    @Operation(summary = "Key in the card terminal's approval code; no card data is accepted")
    public PaymentDtos.IntentResponse capture(
            @PathVariable UUID id, @Valid @RequestBody PaymentDtos.CaptureRequest request) {
        scoped(id);
        return PaymentDtos.IntentResponse.from(
                intents.capture(id, request.approvalCode(), request.terminalReference()));
    }

    @PostMapping("/{id}/decline")
    @PreAuthorize("hasAuthority('payment:take')")
    @Operation(summary = "The terminal declined the card; the sale is told and compensates")
    public PaymentDtos.IntentResponse decline(
            @PathVariable UUID id, @Valid @RequestBody PaymentDtos.DeclineRequest request) {
        scoped(id);
        return PaymentDtos.IntentResponse.from(intents.decline(id, request.reason()));
    }

    private PaymentIntent scoped(UUID id) {
        PaymentIntent intent = intents.require(id);
        branchAccess.requireAccess(intent.getBranchId());
        return intent;
    }
}
