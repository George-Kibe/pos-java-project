package com.pos.catalog.api;

import java.net.URI;
import java.util.List;
import java.util.Map;

import jakarta.validation.Valid;

import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.transaction.annotation.Transactional;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.catalog.api.dto.CatalogDtos;
import com.pos.catalog.domain.Category;
import com.pos.catalog.domain.TaxClass;
import com.pos.catalog.repository.CategoryRepository;
import com.pos.catalog.repository.TaxClassRepository;
import com.pos.catalog.repository.UnitOfMeasureRepository;
import com.pos.common.error.Errors;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Categories, tax classes and units of measure: the vocabulary products are described in. */
@RestController
@RequestMapping("/api/v1")
@RequiredArgsConstructor
@Tag(name = "Reference data")
public class ReferenceDataController {

    private final CategoryRepository categories;
    private final TaxClassRepository taxClasses;
    private final UnitOfMeasureRepository unitsOfMeasure;
    private final com.pos.catalog.repository.ScaleBarcodeRuleRepository scaleRules;

    @GetMapping("/categories")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "List categories")
    public List<CatalogDtos.CategoryResponse> listCategories() {
        return categories.findAll().stream()
                .map(
                        category ->
                                new CatalogDtos.CategoryResponse(
                                        category.getId(),
                                        category.getCode(),
                                        category.getName(),
                                        category.getParent() == null
                                                ? null
                                                : category.getParent().getId(),
                                        category.isActive()))
                .toList();
    }

    @PostMapping("/categories")
    @PreAuthorize("hasAuthority('product:manage')")
    @Transactional
    @Operation(summary = "Create a category")
    public ResponseEntity<CatalogDtos.CategoryResponse> createCategory(
            @Valid @RequestBody CatalogDtos.CategoryRequest request) {

        String code = request.code().trim().toUpperCase(java.util.Locale.ROOT);
        if (categories.existsByCode(code)) {
            throw new Errors.ConflictException(
                    "category.code_taken", "A category with that code already exists.");
        }
        Category category = new Category(code, request.name().trim());
        if (request.parentId() != null) {
            category.setParent(
                    categories
                            .findById(request.parentId())
                            .orElseThrow(
                                    () ->
                                            Errors.NotFoundException.of(
                                                    "Category", request.parentId())));
        }
        categories.save(category);

        CatalogDtos.CategoryResponse body =
                new CatalogDtos.CategoryResponse(
                        category.getId(),
                        category.getCode(),
                        category.getName(),
                        request.parentId(),
                        category.isActive());
        return ResponseEntity.created(URI.create("/api/v1/categories/" + body.id())).body(body);
    }

    @GetMapping("/tax-classes")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "List tax classes with their effective-dated rates")
    public List<CatalogDtos.TaxClassResponse> listTaxClasses() {
        return taxClasses.findAllByOrderByCodeAsc().stream()
                .map(ReferenceDataController::toResponse)
                .toList();
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

    @GetMapping("/units-of-measure")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "List units of measure")
    public List<Map<String, Object>> listUnitsOfMeasure() {
        return unitsOfMeasure.findAll().stream()
                .map(
                        uom ->
                                Map.<String, Object>of(
                                        "id", uom.getId(),
                                        "code", uom.getCode(),
                                        "name", uom.getName(),
                                        "allowsDecimal", uom.isAllowsDecimal(),
                                        "decimalPlaces", uom.getDecimalPlaces()))
                .toList();
    }

    private static CatalogDtos.TaxClassResponse toResponse(TaxClass taxClass) {
        return new CatalogDtos.TaxClassResponse(
                taxClass.getId(),
                taxClass.getCode(),
                taxClass.getName(),
                taxClass.getDescription(),
                taxClass.getRates().stream()
                        .map(
                                rate ->
                                        new CatalogDtos.TaxRateResponse(
                                                rate.getRate(),
                                                rate.getValidFrom(),
                                                rate.getValidTo()))
                        .toList());
    }
}
