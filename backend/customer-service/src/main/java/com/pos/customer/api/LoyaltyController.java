package com.pos.customer.api;

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
import org.springframework.web.bind.annotation.RestController;

import com.pos.common.web.PageResponse;
import com.pos.customer.api.dto.CustomerDtos;
import com.pos.customer.service.LoyaltyQueryService;
import com.pos.customer.service.LoyaltyService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

@RestController
@RequestMapping("/api/v1/loyalty")
@RequiredArgsConstructor
@Tag(name = "Loyalty")
public class LoyaltyController {

    private final LoyaltyService loyalty;
    private final LoyaltyQueryService queries;

    @GetMapping("/accounts/{customerId}")
    @PreAuthorize("hasAuthority('customer:view')")
    @Operation(summary = "A member's balance, what it is worth, and what lapses within 30 days")
    public CustomerDtos.AccountResponse account(@PathVariable UUID customerId) {
        return CustomerDtos.AccountResponse.from(queries.requireSnapshot(customerId));
    }

    @GetMapping("/accounts/{customerId}/transactions")
    @PreAuthorize("hasAuthority('customer:view')")
    @Operation(summary = "Where the points came from and went, newest first")
    public PageResponse<CustomerDtos.TransactionResponse> transactions(
            @PathVariable UUID customerId, @PageableDefault(size = 50) Pageable pageable) {
        return PageResponse.of(
                loyalty.history(loyalty.require(customerId).getId(), pageable),
                CustomerDtos.TransactionResponse::from);
    }

    @PostMapping("/adjustments")
    @PreAuthorize("hasAuthority('loyalty:adjust')")
    @Operation(summary = "Move a member's points by hand, with a reason; recorded with the actor")
    public CustomerDtos.TransactionResponse adjust(
            @Valid @RequestBody CustomerDtos.AdjustmentRequest request) {
        return CustomerDtos.TransactionResponse.from(
                loyalty.adjust(request.customerId(), request.points(), request.reason()));
    }

    @GetMapping("/tiers")
    @PreAuthorize("hasAuthority('customer:view')")
    @Operation(summary = "The ladder, as configured")
    public List<CustomerDtos.TierResponse> tiers() {
        return loyalty.activeTiers().stream().map(CustomerDtos.TierResponse::from).toList();
    }
}
