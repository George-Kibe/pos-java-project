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
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.catalog.api.dto.CatalogDtos;
import com.pos.catalog.domain.Promotion;
import com.pos.catalog.service.PricingRequestSpec;
import com.pos.catalog.service.PromotionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/** Promotions, and the builder's live preview. Read with {@code product:view}. */
@RestController
@RequestMapping("/api/v1/promotions")
@RequiredArgsConstructor
@Tag(name = "Promotions")
public class PromotionController {

    private final PromotionService promotions;

    @GetMapping
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "Every promotion, in the order pricing weighs them")
    public List<CatalogDtos.PromotionResponse> list() {
        return promotions.all().stream().map(CatalogDtos.PromotionResponse::from).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "One promotion")
    public CatalogDtos.PromotionResponse get(@PathVariable UUID id) {
        return CatalogDtos.PromotionResponse.from(promotions.require(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('promotion:manage')")
    @Operation(summary = "Create a promotion")
    public ResponseEntity<CatalogDtos.PromotionResponse> create(
            @Valid @RequestBody CatalogDtos.PromotionRequest request) {
        Promotion promotion = promotions.create(request.code(), request.definition());
        return ResponseEntity.created(URI.create("/api/v1/promotions/" + promotion.getId()))
                .body(CatalogDtos.PromotionResponse.from(promotion));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('promotion:manage')")
    @Operation(summary = "Change a promotion; its code never changes")
    public CatalogDtos.PromotionResponse update(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.PromotionRequest request) {
        return CatalogDtos.PromotionResponse.from(promotions.update(id, request.definition()));
    }

    @PutMapping("/{id}/active")
    @PreAuthorize("hasAuthority('promotion:manage')")
    @Operation(summary = "Start or stop a promotion")
    public CatalogDtos.PromotionResponse setActive(
            @PathVariable UUID id, @RequestParam boolean active) {
        return CatalogDtos.PromotionResponse.from(promotions.setActive(id, active));
    }

    @PostMapping("/preview")
    @PreAuthorize("hasAuthority('promotion:manage')")
    @Operation(
            summary =
                    "What a product would cost with this promotion live - a draft, or an edit of"
                            + " one - beside every other live promotion. Nothing is saved")
    public CatalogDtos.PriceResponse preview(
            @Valid @RequestBody CatalogDtos.PromotionPreviewRequest request) {
        return CatalogDtos.PriceResponse.from(
                promotions.preview(
                        request.promotionId(),
                        request.promotion().code(),
                        request.promotion().definition(),
                        new PricingRequestSpec(
                                request.productId(),
                                null,
                                null,
                                request.quantity(),
                                request.branchId(),
                                Boolean.TRUE.equals(request.member()),
                                null)));
    }
}
