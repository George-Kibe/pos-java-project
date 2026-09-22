package com.pos.sales.api;

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

import com.pos.common.security.AuthenticatedUser;
import com.pos.common.security.BranchAccessGuard;
import com.pos.sales.api.dto.SalesDtos;
import com.pos.sales.domain.Cart;
import com.pos.sales.service.CartService;
import com.pos.sales.service.TillSessionService;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * The basket at the lane.
 *
 * <p>Every mutating call accepts an {@code Idempotency-Key}; a lane that lost its connection
 * retries with the same key and gets the first answer rather than a second tin. The caller's token
 * is forwarded to catalog and inventory, so no service credential exists to leak.
 */
@RestController
@RequestMapping("/api/v1/carts")
@RequiredArgsConstructor
@Tag(name = "Carts")
public class CartController {

    private final CartService carts;
    private final TillSessionService sessions;
    private final BranchAccessGuard branchAccess;

    @PostMapping
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Start a basket on an open shift")
    public ResponseEntity<SalesDtos.CartResponse> open(
            @Valid @RequestBody SalesDtos.OpenCartRequest request) {
        // Checked against the shift before anything is written: checking the new cart afterwards
        // would answer 403 with the cart already committed on another branch's till.
        branchAccess.requireAccess(sessions.require(request.tillSessionId()).getBranchId());
        Cart cart = carts.open(request.tillSessionId(), request.customerId(), request.isMember());
        return ResponseEntity.created(URI.create("/api/v1/carts/" + cart.getId()))
                .body(SalesDtos.CartResponse.from(cart));
    }

    @GetMapping("/{id}")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "One basket, priced by the server")
    public SalesDtos.CartResponse get(@PathVariable UUID id) {
        return SalesDtos.CartResponse.from(scoped(id));
    }

    @GetMapping("/suspended")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Baskets parked at a branch")
    public List<SalesDtos.CartResponse> suspended(@RequestParam UUID branchId) {
        branchAccess.requireAccess(branchId);
        return carts.suspended(branchId).stream().map(SalesDtos.CartResponse::from).toList();
    }

    @PostMapping("/{id}/lines")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Add a product, or increase the line already there")
    public SalesDtos.CartResponse addLine(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.AddLineRequest request) {
        scoped(id);
        return SalesDtos.CartResponse.from(
                carts.addLine(
                        id,
                        request.productId(),
                        request.sku(),
                        request.barcode(),
                        request.quantity(),
                        request.isWeighed(),
                        bearerToken()));
    }

    @PutMapping("/{id}/lines/{lineId}/quantity")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Change a line's quantity")
    public SalesDtos.CartResponse changeQuantity(
            @PathVariable UUID id,
            @PathVariable UUID lineId,
            @Valid @RequestBody SalesDtos.QuantityRequest request) {
        scoped(id);
        return SalesDtos.CartResponse.from(
                carts.changeQuantity(id, lineId, request.quantity(), bearerToken()));
    }

    @PostMapping("/{id}/lines/{lineId}/void")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Remove a line, keeping it on the record")
    public SalesDtos.CartResponse voidLine(
            @PathVariable UUID id,
            @PathVariable UUID lineId,
            @Valid @RequestBody SalesDtos.ReasonRequest request) {
        scoped(id);
        return SalesDtos.CartResponse.from(carts.voidLine(id, lineId, request.reason()));
    }

    @PostMapping("/{id}/lines/{lineId}/price-override")
    @PreAuthorize("hasAuthority('price:override')")
    @Operation(summary = "Set a line's price, with a reason; audited with the value given away")
    public SalesDtos.CartResponse overridePrice(
            @PathVariable UUID id,
            @PathVariable UUID lineId,
            @Valid @RequestBody SalesDtos.PriceOverrideRequest request) {
        scoped(id);
        // The approver is whoever holds price:override and made this call, taken from the token.
        UUID approver = AuthenticatedUser.require().userId();
        return SalesDtos.CartResponse.from(
                carts.overridePrice(id, lineId, request.unitPrice(), request.reason(), approver));
    }

    @PostMapping("/{id}/suspend")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Park a basket; the response carries the code for the ticket")
    public SalesDtos.CartResponse suspend(@PathVariable UUID id) {
        scoped(id);
        return SalesDtos.CartResponse.from(carts.suspend(id));
    }

    @PostMapping("/recall")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Bring a parked basket back by its code")
    public SalesDtos.CartResponse recall(@Valid @RequestBody SalesDtos.RecallRequest request) {
        branchAccess.requireAccess(request.branchId());
        return SalesDtos.CartResponse.from(carts.recall(request.branchId(), request.code()));
    }

    @PutMapping("/{id}/customer")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Attach a customer; membership reprices the basket")
    public SalesDtos.CartResponse attachCustomer(
            @PathVariable UUID id, @Valid @RequestBody SalesDtos.CustomerRequest request) {
        scoped(id);
        return SalesDtos.CartResponse.from(
                carts.attachCustomer(id, request.customerId(), request.isMember(), bearerToken()));
    }

    @PostMapping("/{id}/abandon")
    @PreAuthorize("hasAuthority('cart:manage')")
    @Operation(summary = "Give up on a basket and release its stock")
    public SalesDtos.CartResponse abandon(@PathVariable UUID id) {
        scoped(id);
        return SalesDtos.CartResponse.from(carts.abandon(id, bearerToken()));
    }

    private Cart scoped(UUID id) {
        Cart cart = carts.require(id);
        branchAccess.requireAccess(cart.getBranchId());
        return cart;
    }

    /** The caller's verified token, forwarded to catalog and inventory on their behalf. */
    private static String bearerToken() {
        return AuthenticatedUser.bearerToken().orElse(null);
    }
}
