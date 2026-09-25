package com.pos.catalog.api;

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

import com.pos.catalog.api.dto.CatalogDtos;
import com.pos.catalog.domain.PriceReview;
import com.pos.catalog.service.PriceReviewService;
import com.pos.common.security.BranchAccessGuard;
import com.pos.common.web.PageResponse;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Prices a delivery's cost has put below target: listed per branch, and settled by setting the
 * suggested (or another) price or by keeping the old one with a reason.
 */
@RestController
@RequestMapping("/api/v1/price-reviews")
@RequiredArgsConstructor
@Tag(name = "Price reviews")
public class PriceReviewController {

    private final PriceReviewService reviews;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(summary = "A branch's price reviews, newest delivery first; open ones by default")
    public PageResponse<CatalogDtos.PriceReviewResponse> list(
            @RequestParam UUID branchId,
            @RequestParam(defaultValue = "OPEN") PriceReview.Status status,
            @PageableDefault(size = 50) Pageable pageable) {
        branchAccess.requireAccess(branchId);
        return PageResponse.of(
                reviews.list(branchId, status, pageable), CatalogDtos.PriceReviewResponse::from);
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(summary = "One price review")
    public CatalogDtos.PriceReviewResponse get(@PathVariable UUID id) {
        PriceReview review = reviews.require(id);
        branchAccess.requireAccess(review.getBranchId());
        return CatalogDtos.PriceReviewResponse.from(review);
    }

    @PostMapping("/{id}/accept")
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(
            summary = "Set the new price and close the review",
            description =
                    "Changes the price the branch was charging: its price list's entry when a list"
                            + " set it, the base price otherwise. Omit the price to take the"
                            + " suggestion.")
    public CatalogDtos.PriceReviewResponse accept(
            @PathVariable UUID id,
            @Valid @RequestBody(required = false) CatalogDtos.PriceReviewAcceptRequest request) {
        requireBranchOf(id);
        return CatalogDtos.PriceReviewResponse.from(
                reviews.accept(id, request == null ? null : request.price()));
    }

    @PostMapping("/{id}/keep")
    @PreAuthorize("hasAuthority('price:manage')")
    @Operation(summary = "Keep the price as it is, for a reason, and close the review")
    public CatalogDtos.PriceReviewResponse keep(
            @PathVariable UUID id, @Valid @RequestBody CatalogDtos.PriceReviewKeepRequest request) {
        requireBranchOf(id);
        return CatalogDtos.PriceReviewResponse.from(reviews.keep(id, request.reason()));
    }

    /** Before the write, not after: a check on the result would come with the price changed. */
    private void requireBranchOf(UUID id) {
        branchAccess.requireAccess(reviews.require(id).getBranchId());
    }
}
