package com.pos.catalog.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.catalog.api.dto.CatalogDtos;
import com.pos.catalog.domain.Brand;
import com.pos.catalog.domain.Category;
import com.pos.catalog.domain.TaxClass;
import com.pos.catalog.domain.UnitOfMeasure;
import com.pos.catalog.service.ReferenceDataService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Categories, brands, units of measure and tax classes: the vocabulary products are described in.
 * Anyone who sees products reads it; products are managed with {@code product:manage}, tax with
 * {@code tax:manage}.
 */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Reference data")
public class ReferenceDataController {

    private final ReferenceDataService reference;
    private final com.pos.catalog.repository.ScaleBarcodeRuleRepository scaleRules;

    // --- categories ---------------------------------------------------------------------------

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "List categories")
    public List<CatalogDtos.CategoryResponse> listCategories() {
        return reference.categories().stream().map(ReferenceDataController::toResponse).toList();
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Create a category")
    public ResponseEntity<CatalogDtos.CategoryResponse> createCategory(
            @Valid @RequestBody CatalogDtos.CategoryRequest request) {
        Category category =
                reference.createCategory(request.code(), request.name(), request.parentId());
        return ResponseEntity.created(URI.create("/api/v1/categories/" + category.getId()))
                .body(toResponse(category));
    }

    @PutMapping("/categories/{id}")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Rename, move or retire a category; its code never changes")
    public CatalogDtos.CategoryResponse updateCategory(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.CategoryUpdateRequest request) {
        return toResponse(
                reference.updateCategory(
                        id, request.name(), request.parentId(), request.isActive()));
    }

    // --- brands -------------------------------------------------------------------------------

    @GetMapping("/brands")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "List brands")
    public List<CatalogDtos.BrandResponse> listBrands() {
        return reference.brands().stream().map(ReferenceDataController::toResponse).toList();
    }

    @PostMapping("/brands")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Create a brand")
    public ResponseEntity<CatalogDtos.BrandResponse> createBrand(
            @Valid @RequestBody CatalogDtos.BrandRequest request) {
        Brand brand = reference.createBrand(request.code(), request.name());
        return ResponseEntity.created(URI.create("/api/v1/brands/" + brand.getId()))
                .body(toResponse(brand));
    }

    @PutMapping("/brands/{id}")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Rename or retire a brand")
    public CatalogDtos.BrandResponse updateBrand(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.BrandUpdateRequest request) {
        return toResponse(reference.updateBrand(id, request.name(), request.isActive()));
    }

    // --- units of measure ---------------------------------------------------------------------

    @GetMapping("/units-of-measure")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "List units of measure")
    public List<CatalogDtos.UnitResponse> listUnitsOfMeasure() {
        return reference.units().stream().map(ReferenceDataController::toResponse).toList();
    }

    @PostMapping("/units-of-measure")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Create a unit of measure")
    public ResponseEntity<CatalogDtos.UnitResponse> createUnit(
            @Valid @RequestBody CatalogDtos.UnitRequest request) {
        UnitOfMeasure unit =
                reference.createUnit(
                        request.code(),
                        request.name(),
                        request.fractional(),
                        request.decimalPlaces() == null ? 0 : request.decimalPlaces());
        return ResponseEntity.created(URI.create("/api/v1/units-of-measure/" + unit.getId()))
                .body(toResponse(unit));
    }

    @PutMapping("/units-of-measure/{id}")
    @PreAuthorize("hasAuthority('product:manage')")
    @Operation(summary = "Rename a unit of measure; whether it allows fractions never changes")
    public CatalogDtos.UnitResponse updateUnit(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.UnitUpdateRequest request) {
        return toResponse(reference.updateUnit(id, request.name()));
    }

    // --- tax ----------------------------------------------------------------------------------

    @GetMapping("/tax-classes")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "List tax classes with their effective-dated rates")
    public List<CatalogDtos.TaxClassResponse> listTaxClasses() {
        return reference.taxClasses().stream().map(ReferenceDataController::toResponse).toList();
    }

    @PostMapping("/tax-classes")
    @PreAuthorize("hasAuthority('tax:manage')")
    @Operation(summary = "Create a tax class with the rate it starts at")
    public ResponseEntity<CatalogDtos.TaxClassResponse> createTaxClass(
            @Valid @RequestBody CatalogDtos.TaxClassRequest request) {
        TaxClass taxClass =
                reference.createTaxClass(
                        request.code(),
                        request.name(),
                        request.description(),
                        request.rate(),
                        request.validFrom());
        return ResponseEntity.created(URI.create("/api/v1/tax-classes/" + taxClass.getId()))
                .body(toResponse(taxClass));
    }

    @PutMapping("/tax-classes/{id}")
    @PreAuthorize("hasAuthority('tax:manage')")
    @Operation(summary = "Rename, describe or retire a tax class")
    public CatalogDtos.TaxClassResponse updateTaxClass(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.TaxClassUpdateRequest request) {
        return toResponse(
                reference.updateTaxClass(
                        id, request.name(), request.description(), request.isActive()));
    }

    @PostMapping("/tax-classes/{id}/rates")
    @PreAuthorize("hasAuthority('tax:manage')")
    @Operation(
            summary =
                    "Change a tax class's rate from a moment on; the current rate closes then."
                            + " Never backdated")
    public CatalogDtos.TaxClassResponse changeRate(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.TaxRateRequest request) {
        return toResponse(reference.changeRate(id, request.rate(), request.validFrom()));
    }

    @PutMapping("/tax-classes/{id}/default")
    @PreAuthorize("hasAuthority('tax:manage')")
    @Operation(summary = "Make this the tax class new products start with")
    public CatalogDtos.TaxClassResponse makeDefault(@PathVariable UUID id) {
        return toResponse(reference.makeDefault(id));
    }

    @GetMapping("/scale-barcode-rules")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(
            summary =
                    "The active scale barcode formats, so an offline lane decodes weighed and"
                            + " priced labels exactly as the server does")
    public List<CatalogDtos.ScaleBarcodeRuleResponse> listScaleBarcodeRules() {
        return scaleRules.findByActiveTrueOrderByPrefixAsc().stream()
                .map(CatalogDtos.ScaleBarcodeRuleResponse::from)
                .toList();
    }

    private static CatalogDtos.CategoryResponse toResponse(Category category) {
        return new CatalogDtos.CategoryResponse(
                category.getId(),
                category.getCode(),
                category.getName(),
                category.getParent() == null ? null : category.getParent().getId(),
                category.isActive());
    }

    private static CatalogDtos.BrandResponse toResponse(Brand brand) {
        return new CatalogDtos.BrandResponse(
                brand.getId(), brand.getCode(), brand.getName(), brand.isActive());
    }

    private static CatalogDtos.UnitResponse toResponse(UnitOfMeasure unit) {
        return new CatalogDtos.UnitResponse(
                unit.getId(),
                unit.getCode(),
                unit.getName(),
                unit.isAllowsDecimal(),
                unit.getDecimalPlaces());
    }

    private static CatalogDtos.TaxClassResponse toResponse(TaxClass taxClass) {
        return new CatalogDtos.TaxClassResponse(
                taxClass.getId(),
                taxClass.getCode(),
                taxClass.getName(),
                taxClass.getDescription(),
                taxClass.getRates().stream()
                        .sorted(
                                java.util.Comparator.comparing(
                                        com.pos.catalog.domain.TaxRate::getValidFrom))
                        .map(
                                rate ->
                                        new CatalogDtos.TaxRateResponse(
                                                rate.getRate(),
                                                rate.getValidFrom(),
                                                rate.getValidTo()))
                        .toList(),
                taxClass.isActive(),
                taxClass.isDefaultClass());
    }
}
