package com.pos.payment.api;

import java.io.IOException;
import java.net.URI;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.format.annotation.DateTimeFormat;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RequestPart;
import org.springframework.web.bind.annotation.RestController;
import org.springframework.web.multipart.MultipartFile;

import com.pos.common.error.Errors;
import com.pos.common.web.PageResponse;
import com.pos.payment.api.dto.PaymentDtos;
import com.pos.payment.domain.ReconciliationRun;
import com.pos.payment.service.ReconciliationService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * M-Pesa statement reconciliation.
 *
 * <p>Organisation-wide, not per branch - one shortcode takes every branch's money - so it is gated
 * on {@code payment:reconcile} alone, which only finance roles carry.
 */
@RestController
@RequestMapping("/api/v1/payments/reconciliation-runs")
@RequiredArgsConstructor
@Tag(name = "Reconciliation")
public class ReconciliationController {

    /** A day's statement for a busy shortcode is a few hundred KB; this is generous. */
    private static final long MAX_STATEMENT_BYTES = 5L * 1024 * 1024;

    private final ReconciliationService reconciliation;

    @PostMapping(consumes = MediaType.MULTIPART_FORM_DATA_VALUE)
    @PreAuthorize("hasAuthority('payment:reconcile')")
    @Operation(summary = "Reconcile a day's M-Pesa statement export against recorded payments")
    public ResponseEntity<PaymentDtos.RunResponse> run(
            @RequestParam @DateTimeFormat(iso = DateTimeFormat.ISO.DATE) LocalDate statementDate,
            @RequestPart("statement") MultipartFile statement)
            throws IOException {
        if (statement.isEmpty()) {
            throw new Errors.BadRequestException(
                    "reconciliation.empty_statement", "The statement file is empty");
        }
        if (statement.getSize() > MAX_STATEMENT_BYTES) {
            throw new Errors.BadRequestException(
                    "reconciliation.statement_too_large", "A statement file is at most 5 MB");
        }
        ReconciliationRun run =
                reconciliation.run(
                        statementDate,
                        statement.getOriginalFilename(),
                        new String(statement.getBytes(), StandardCharsets.UTF_8));
        return ResponseEntity.created(
                        URI.create("/api/v1/payments/reconciliation-runs/" + run.getId()))
                .body(PaymentDtos.RunResponse.from(run, reconciliation.itemsOf(run.getId())));
    }

    @GetMapping
    @PreAuthorize("hasAuthority('payment:reconcile')")
    @Operation(summary = "Past reconciliation runs, newest statement first")
    public PageResponse<PaymentDtos.RunResponse> list(
            @PageableDefault(size = 30) Pageable pageable) {
        return PageResponse.of(
                reconciliation.list(pageable), run -> PaymentDtos.RunResponse.from(run, null));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('payment:reconcile')")
    @Operation(summary = "One run, item by item")
    public PaymentDtos.RunResponse get(@PathVariable UUID id) {
        return PaymentDtos.RunResponse.from(reconciliation.require(id), reconciliation.itemsOf(id));
    }
}
