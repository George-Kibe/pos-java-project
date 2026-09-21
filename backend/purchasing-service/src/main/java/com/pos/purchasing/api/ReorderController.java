package com.pos.purchasing.api;

import java.util.List;
import java.util.UUID;

import jakarta.validation.Valid;

import org.springframework.security.access.prepost.PreAuthorize;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RequestParam;
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.security.BranchAccessGuard;
import com.pos.purchasing.api.dto.PurchasingDtos;
import com.pos.purchasing.service.ReorderService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * What is about to run out.
 *
 * <p>Built from inventory's low-stock events, so the list exists at the moment a buyer could still
 * do something about it rather than after the nightly report.
 */
@RestController
@RequestMapping("/api/v1/reorder-suggestions")
@RequiredArgsConstructor
@Tag(name = "Reorder suggestions")
public class ReorderController {

    private final ReorderService reorders;
    private final BranchAccessGuard branchAccess;

    @GetMapping
    @PreAuthorize("hasAuthority('purchase:view')")
    @Operation(summary = "Open reorder suggestions at a branch")
    public List<PurchasingDtos.ReorderSuggestionResponse> open(@RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return reorders.open(branchId).stream()
                .map(PurchasingDtos.ReorderSuggestionResponse::from)
                .toList();
    }

    @PostMapping("/{id}/dismiss")
    @PreAuthorize("hasAuthority('purchase:create')")
    @Operation(summary = "Reject a suggestion, with a reason, so it is not proposed again")
    public PurchasingDtos.ReorderSuggestionResponse dismiss(
            @PathVariable UUID id, @Valid @RequestBody PurchasingDtos.ReasonRequest request) {
        return PurchasingDtos.ReorderSuggestionResponse.from(
                reorders.dismiss(id, request.reason()));
    }
}
