package com.pos.catalog.api;

import java.net.URI;
import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.data.domain.Pageable;
import org.springframework.data.web.PageableDefault;
import org.springframework.http.ResponseEntity;
import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.DeleteMapping;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.PutMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.catalog.api.dto.CatalogDtos;
import com.pos.catalog.domain.PriceList;
import com.pos.catalog.domain.PriceListItem;
import com.pos.catalog.service.PriceListService;
import com.pos.common.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Price lists per branch (or group-wide), with a price for each product that differs from its base
 * price. Read with {@code product:view}, changed with {@code price:manage}.
 */
@RestController
@RequestMapping("/api/v1/price-lists")
@RequiredArgsConstructor
@Tag(name = "Price lists")
public class PriceListController {

    private final PriceListService lists;

    @GetMapping
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "Every price list, highest priority first")
    public List<CatalogDtos.PriceListResponse> list() {
        return lists.all().stream().map(this::toResponse).toList();
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "One price list")
    public CatalogDtos.PriceListResponse get(@PathVariable UUID id) {
        return toResponse(lists.require(id));
    }

    @PostMapping
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(summary = "Create a price list")
    public ResponseEntity<CatalogDtos.PriceListResponse> create(
            @Valid @RequestBody CatalogDtos.PriceListRequest request) {
        PriceList list = lists.create(request.code(), definition(request));
        return ResponseEntity.created(URI.create("/api/v1/price-lists/" + list.getId()))
                .body(toResponse(list));
    }

    @PutMapping("/{id}")
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(summary = "Change a price list's name, branch, priority, window or status")
    public CatalogDtos.PriceListResponse update(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.PriceListRequest request) {
        return toResponse(lists.update(id, definition(request)));
    }

    @GetMapping("/{id}/items")
    @PreAuthorize("hasAuthority('product:view')")
    @Operation(summary = "The list's prices, a page at a time")
    public PageResponse<CatalogDtos.PriceListItemResponse> items(
            @PathVariable UUID id, @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(lists.items(id, pageable), PriceListController::toResponse);
    }

    @PutMapping("/{id}/items/{productId}")
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(summary = "Set a product's price on the list")
    public CatalogDtos.PriceListItemResponse setPrice(
            @PathVariable UUID id,
            @PathVariable UUID productId,
            @Valid @RequestBody CatalogDtos.PriceListItemRequest request) {
        return toResponse(lists.setPrice(id, productId, request.price()));
    }

    @DeleteMapping("/{id}/items/{productId}")
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(summary = "Take a product off the list, back to its base price")
    public ResponseEntity<Void> removePrice(@PathVariable UUID id, @PathVariable UUID productId) {
        lists.removePrice(id, productId);
        return ResponseEntity.noContent().build();
    }

    private static PriceListService.Definition definition(CatalogDtos.PriceListRequest request) {
        return new PriceListService.Definition(
                request.name(),
                request.branchId(),
                request.priorityOrDefault(),
                request.validFrom(),
                request.validTo(),
                request.isActive());
    }

    private CatalogDtos.PriceListResponse toResponse(PriceList list) {
        return new CatalogDtos.PriceListResponse(
                list.getId(),
                list.getCode(),
                list.getName(),
                list.getBranchId(),
                list.getPriority(),
                list.getValidFrom(),
                list.getValidTo(),
                list.isActive(),
                lists.itemCount(list.getId()));
    }

    private static CatalogDtos.PriceListItemResponse toResponse(PriceListItem item) {
        return new CatalogDtos.PriceListItemResponse(
                item.getProduct().getId(),
                item.getProduct().getSku(),
                item.getProduct().getName(),
                item.getProduct().getBasePrice(),
                item.getPrice(),
                item.getCurrency());
    }
}
