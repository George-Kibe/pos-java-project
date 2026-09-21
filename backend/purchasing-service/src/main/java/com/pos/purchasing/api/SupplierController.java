package com.pos.purchasing.api;

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
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.web.PageResponse;
import com.pos.purchasing.api.dto.PurchasingDtos;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierStatus;
import com.pos.purchasing.service.SupplierQueryService;
import com.pos.purchasing.service.SupplierService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Who we buy from, and at what price. */
@RestController
@RequestMapping("/api/v1/suppliers")
@RequiredArgsConstructor
@Tag(name = "Suppliers")
public class SupplierController {

    private final SupplierService suppliers;
    private final SupplierQueryService query;

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "List suppliers")
    public PageResponse<PurchasingDtos.SupplierResponse> list(
            @RequestParam(required = false) SupplierStatus status,
            @RequestParam(required = false) String q,
            @PageableDefault(size = 50) Pageable pageable) {

        // Two code paths rather than a null-tolerant query: an untyped null inside lower() makes
        // PostgreSQL reject the statement outright.
        return PageResponse.of(
                q == null || q.isBlank()
                        ? suppliers.list(status, pageable)
                        : suppliers.search(q, pageable),
                PurchasingDtos.SupplierResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "One supplier")
    public PurchasingDtos.SupplierResponse get(@PathVariable UUID id) {
        return PurchasingDtos.SupplierResponse.from(suppliers.require(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('supplier:manage')")
    @Operation(summary = "Create a supplier")
    public ResponseEntity<PurchasingDtos.SupplierResponse> create(
            @Valid @RequestBody PurchasingDtos.SupplierRequest request) {

        Supplier created = suppliers.create(toEntity(request));
        return ResponseEntity.created(URI.create("/api/v1/suppliers/" + created.getId()))
                .body(PurchasingDtos.SupplierResponse.from(created));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('supplier:manage')")
    @Operation(summary = "Update a supplier")
    public PurchasingDtos.SupplierResponse update(
            @PathVariable UUID id, @Valid @RequestBody PurchasingDtos.SupplierRequest request) {
        return PurchasingDtos.SupplierResponse.from(suppliers.update(id, toEntity(request)));
    }

    @PutMapping("/{id}/status")
    @PreAuthorize("hasAuthority('supplier:manage')")
    @Operation(summary = "Put a supplier on hold, or take them off it")
    public PurchasingDtos.SupplierResponse changeStatus(
            @PathVariable UUID id,
            @Valid @RequestBody PurchasingDtos.SupplierStatusRequest request) {
        return PurchasingDtos.SupplierResponse.from(suppliers.changeStatus(id, request.status()));
    }

    @GetMapping("/{id}/products")
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "What a supplier sells us")
    public List<PurchasingDtos.SupplierProductResponse> products(@PathVariable UUID id) {
        return query.productsOf(id).stream()
                .map(PurchasingDtos.SupplierProductResponse::from)
                .toList();
    }

    @PostMapping("/{id}/products")
    @PreAuthorize("hasAuthority('supplier:manage')")
    @Operation(summary = "Add or re-price a product on a supplier's price list")
    public PurchasingDtos.SupplierProductResponse addProduct(
            @PathVariable UUID id,
            @Valid @RequestBody PurchasingDtos.SupplierProductRequest request) {

        return PurchasingDtos.SupplierProductResponse.from(
                suppliers.addProduct(
                        id,
                        request.productId(),
                        request.sku(),
                        request.productName(),
                        request.agreedUnitCost(),
                        request.supplierSku(),
                        request.minimumOrderQty(),
                        request.leadTimeDays(),
                        request.isPreferred()));
    }

    private static Supplier toEntity(PurchasingDtos.SupplierRequest request) {
        Supplier supplier = new Supplier(request.code(), request.name());
        supplier.setContactName(request.contactName());
        supplier.setEmail(request.email());
        supplier.setPhone(request.phone());
        supplier.setAddress(request.address());
        supplier.setTaxIdentifier(request.taxIdentifier());
        supplier.setPaymentTermsDays(request.paymentTermsOrDefault());
        supplier.setLeadTimeDays(request.leadTimeOrDefault());
        supplier.setCurrency(request.currencyOrDefault());
        supplier.setNotes(request.notes());
        return supplier;
    }
}
