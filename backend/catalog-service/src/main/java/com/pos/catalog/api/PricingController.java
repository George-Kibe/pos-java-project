package com.pos.catalog.api;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.catalog.api.dto.CatalogDtos;
import com.pos.catalog.service.BarcodeScanService;
import com.pos.catalog.service.PricingRequestSpec;
import com.pos.catalog.service.PricingService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * What a line costs, and what a scan means.
 *
 * <p>Read-only and gated on {@code product:view}: a cashier prices a basket, they do not edit the
 * catalog to do it.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Pricing")
public class PricingController {

    private final PricingService pricing;
    private final BarcodeScanService scanning;

    @PostMapping("/pricing/resolve")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "Price one or more lines, with the full breakdown")
    public List<CatalogDtos.PriceResponse> resolve(
            @Valid @RequestBody CatalogDtos.PriceRequest request) {

        List<PricingRequestSpec> specs =
                request.lines().stream()
                        .map(
                                line ->
                                        new PricingRequestSpec(
                                                line.productId(),
                                                line.sku(),
                                                line.barcode(),
                                                line.quantity(),
                                                request.branchId(),
                                                request.isMember(),
                                                request.at()))
                        .toList();

        return pricing.resolveAll(specs).stream().map(CatalogDtos.PriceResponse::from).toList();
    }

    @GetMapping("/products/scan")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "Resolve a scanned barcode, including scale barcodes, and price it")
    public CatalogDtos.ScanResponse scan(
            @RequestParam String barcode,
            @RequestParam(required = false) UUID branchId,
            @RequestParam(defaultValue = "false") boolean member,
            @RequestParam(required = false) Instant at) {

        return CatalogDtos.ScanResponse.from(scanning.scan(barcode, branchId, member, at));
    }
}
