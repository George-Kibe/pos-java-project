package com.pos.sales.service;

import java.math.BigDecimal;
import java.security.SecureRandom;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;
import com.pos.sales.client.InventoryClient;
import com.pos.sales.domain.Cart;
import com.pos.sales.domain.CartLine;
import com.pos.sales.domain.CartStatus;
import com.pos.sales.domain.PriceOverride;
import com.pos.sales.domain.PriceSource;
import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.totals.SaleTotals;
import com.pos.sales.domain.totals.SaleTotalsCalculator;
import com.pos.sales.repository.CartRepository;
import com.pos.sales.repository.PriceOverrideRepository;

import lombok.RequiredArgsConstructor;

/**
 * A basket being built at the lane.
 *
 * <p>Every change reprices the affected line through catalog and re-totals the basket, so the
 * figure on the customer display is always the server's. The client is never the source of a price.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class CartService {

    private static final SecureRandom RANDOM = new SecureRandom();

    private final CartRepository carts;
    private final PriceOverrideRepository overrides;
    private final TillSessionService sessions;
    private final PricingService pricing;
    private final InventoryClient inventory;

    public Cart require(UUID id) {
        return carts.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Cart", id));
    }

    public List<Cart> suspended(UUID branchId) {
        return carts.findByBranchIdAndStatusOrderBySuspendedAtDesc(branchId, CartStatus.SUSPENDED);
    }

    @Transactional
    public Cart open(UUID tillSessionId, UUID customerId, boolean member) {
        TillSession session = sessions.require(tillSessionId);
        if (!session.getStatus().acceptsSales()) {
            throw new Errors.ConflictException(
                    "till.not_open", "This shift is %s".formatted(session.getStatus()));
        }

        Cart cart = new Cart(session, session.getBranchId());
        cart.setCustomerId(customerId);
        cart.setMember(member);
        return carts.save(cart);
    }

    /**
     * Adds a product, or increases the line that is already there.
     *
     * <p>Merging rather than appending, because a cashier scanning the same tin four times means
     * four of them, not four lines - except for a weighed item, where each weighing is its own line
     * and merging would lose the individual weights.
     */
    @Transactional
    public Cart addLine(
            UUID cartId,
            UUID productId,
            String sku,
            String barcode,
            BigDecimal quantity,
            boolean weighed,
            String authorization) {

        Cart cart = requireEditable(cartId);
        if (quantity == null || quantity.signum() <= 0) {
            throw new Errors.BusinessRuleException(
                    "cart.invalid_quantity", "A line needs a quantity greater than zero");
        }

        CartLine line =
                weighed
                        ? null
                        : cart.activeLines().stream()
                                .filter(existing -> existing.getProductId().equals(productId))
                                .filter(existing -> !existing.isOverridden())
                                .findFirst()
                                .orElse(null);

        if (line == null) {
            line = new CartLine(cart, cart.nextLineNumber(), productId, quantity);
            line.setSku(sku);
            line.setBarcode(barcode);
            cart.getLines().add(line);
        } else {
            line.setQuantity(line.getQuantity().add(quantity));
        }

        pricing.applyPrices(List.of(line), cart.getBranchId(), cart.isMember(), authorization);
        retotal(cart);
        Cart saved = carts.save(cart);

        // A soft hold so a second lane cannot promise the same last unit. Best-effort by design:
        // see InventoryClient.
        inventory.reserve(productId, cart.getBranchId(), quantity, cart.getId(), authorization);
        return saved;
    }

    @Transactional
    public Cart changeQuantity(
            UUID cartId, UUID lineId, BigDecimal quantity, String authorization) {

        Cart cart = requireEditable(cartId);
        CartLine line = lineOf(cart, lineId);

        if (quantity == null || quantity.signum() <= 0) {
            throw new Errors.BusinessRuleException(
                    "cart.invalid_quantity", "Use void to remove a line, not a zero quantity");
        }
        line.setQuantity(quantity);

        if (line.isOverridden()) {
            // Keep the decided unit price; only the amounts follow the quantity.
            pricing.applyOverriddenQuantity(line);
        } else {
            pricing.applyPrices(List.of(line), cart.getBranchId(), cart.isMember(), authorization);
        }

        retotal(cart);
        return carts.save(cart);
    }

    /** Removes a line, keeping it on the record. */
    @Transactional
    public Cart voidLine(UUID cartId, UUID lineId, String reason) {
        Cart cart = requireEditable(cartId);
        CartLine line = lineOf(cart, lineId);

        line.voidLine(reason);
        retotal(cart);
        return carts.save(cart);
    }

    /**
     * A supervisor sets a price.
     *
     * <p>Audited in its own table with the value given away, because "who has been discounting" is
     * asked across sales rather than within one. The reason and the approver are both required, and
     * the database enforces that they arrive together.
     */
    @Transactional
    public Cart overridePrice(
            UUID cartId, UUID lineId, BigDecimal newUnitPrice, String reason, UUID approvedBy) {

        Cart cart = requireEditable(cartId);
        CartLine line = lineOf(cart, lineId);

        if (newUnitPrice == null || newUnitPrice.signum() < 0) {
            throw new Errors.BusinessRuleException(
                    "cart.invalid_override", "An overridden price cannot be negative");
        }
        if (reason == null || reason.isBlank()) {
            throw new Errors.BusinessRuleException(
                    "cart.override_reason_required", "A price override needs a reason");
        }

        BigDecimal original =
                line.getOriginalUnitPrice() != null
                        ? line.getOriginalUnitPrice()
                        : line.getUnitPrice();

        line.setOriginalUnitPrice(original);
        line.setUnitPrice(newUnitPrice);
        line.setPriceSource(PriceSource.OVERRIDE);
        line.setOverrideReason(reason);
        line.setOverriddenBy(approvedBy);
        pricing.applyOverriddenQuantity(line);

        retotal(cart);
        Cart saved = carts.save(cart);

        overrides.save(new PriceOverride(line, original, newUnitPrice, reason, approvedBy));
        return saved;
    }

    /** Parks a basket and returns the code printed on the ticket. */
    @Transactional
    public Cart suspend(UUID cartId) {
        Cart cart = requireEditable(cartId);
        cart.setStatus(CartStatus.SUSPENDED);
        cart.setSuspendedAt(Instant.now());
        cart.setSuspendCode(nextSuspendCode(cart.getBranchId()));
        return carts.save(cart);
    }

    /** Brings a parked basket back to a lane. */
    @Transactional
    public Cart recall(UUID branchId, String suspendCode) {
        Cart cart =
                carts.findByBranchIdAndSuspendCodeAndStatus(
                                branchId, suspendCode, CartStatus.SUSPENDED)
                        .orElseThrow(
                                () -> Errors.NotFoundException.of("Suspended cart", suspendCode));

        cart.setStatus(CartStatus.OPEN);
        cart.setSuspendCode(null);
        cart.setSuspendedAt(null);
        return carts.save(cart);
    }

    @Transactional
    public Cart attachCustomer(UUID cartId, UUID customerId, boolean member, String authorization) {
        Cart cart = requireEditable(cartId);
        cart.setCustomerId(customerId);

        if (cart.isMember() != member) {
            // Membership changes which promotions apply, so the whole basket is repriced.
            cart.setMember(member);
            pricing.applyPrices(cart.activeLines(), cart.getBranchId(), member, authorization);
            retotal(cart);
        }
        return carts.save(cart);
    }

    @Transactional
    public Cart abandon(UUID cartId, String authorization) {
        Cart cart = require(cartId);
        cart.setStatus(CartStatus.ABANDONED);
        inventory.release(cart.getId(), authorization);
        return carts.save(cart);
    }

    /** Re-sums the basket from its active lines. */
    void retotal(Cart cart) {
        SaleTotals totals =
                SaleTotalsCalculator.total(PricingService.toPricedLines(cart.activeLines()));
        cart.setNetTotal(totals.net());
        cart.setTaxTotal(totals.tax());
        cart.setDiscountTotal(totals.discount());
        cart.setGrandTotal(totals.grand());
    }

    private Cart requireEditable(UUID cartId) {
        Cart cart = require(cartId);
        if (!cart.getStatus().isEditable()) {
            throw new Errors.ConflictException(
                    "cart.not_editable",
                    "This basket is %s and cannot be changed".formatted(cart.getStatus()));
        }
        return cart;
    }

    private static CartLine lineOf(Cart cart, UUID lineId) {
        return cart.getLines().stream()
                .filter(line -> line.getId().equals(lineId))
                .findFirst()
                .orElseThrow(() -> Errors.NotFoundException.of("Cart line", lineId));
    }

    /**
     * A short code a customer can be called back by.
     *
     * <p>Four digits, which is short enough to read aloud. Collisions are possible and the unique
     * index catches them, so a retry is all that is needed - deliberately not a long unguessable
     * token, because nobody would type it.
     */
    private String nextSuspendCode(UUID branchId) {
        for (int attempt = 0; attempt < 20; attempt++) {
            String code = "%04d".formatted(RANDOM.nextInt(10_000));
            if (carts.findByBranchIdAndSuspendCodeAndStatus(branchId, code, CartStatus.SUSPENDED)
                    .isEmpty()) {
                return code;
            }
        }
        throw new Errors.ConflictException(
                "cart.no_suspend_code",
                "Too many baskets are parked at this branch; clear some before suspending more");
    }

    static UUID currentActor() {
        return AuthenticatedUser.currentUserId();
    }
}
