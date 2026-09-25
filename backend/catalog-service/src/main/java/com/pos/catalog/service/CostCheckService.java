package com.pos.catalog.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.pricing.MarginCheck;
import com.pos.catalog.repository.ProductRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * Judges what goods cost against what they sell for: the tax a supplier's price carried, the price
 * a branch charges, and the margin that leaves against the product's target.
 *
 * <p>Purchasing asks it for the tax rate when a delivery is keyed in with VAT-inclusive prices; the
 * delivery screen asks it while the costs are typed; and a posted delivery is checked by it before
 * a price review is opened.
 */
@Service
@RequiredArgsConstructor
public class CostCheckService {

    private final ProductRepository products;
    private final PricingService pricing;

    /** One product's cost, as keyed in. */
    public record CostLine(UUID productId, BigDecimal unitCost) {}

    /**
     * @param unitCost the cost as keyed in
     * @param taxRate the product's rate at the time, as a fraction
     * @param price the branch's regular price, as entered in the catalogue
     */
    public record CostCheck(
            UUID productId,
            String sku,
            String name,
            BigDecimal unitCost,
            BigDecimal taxRate,
            PricingService.ResolvedPrice price,
            boolean priceIncludesTax,
            MarginCheck margin) {}

    /**
     * @param branchId whose price to judge against; null for the base price alone
     * @param at when; null for now
     * @param costIncludesTax whether the costs keyed in carry VAT, which is then taken out
     */
    @Transactional(readOnly = true)
    public List<CostCheck> check(
            UUID branchId, Instant at, boolean costIncludesTax, List<CostLine> lines) {
        Instant when = at == null ? Instant.now() : at;
        return lines.stream().map(line -> check(branchId, when, costIncludesTax, line)).toList();
    }

    /** Within the caller's transaction: the product's category chain is read lazily. */
    CostCheck check(UUID branchId, Instant at, boolean costIncludesTax, CostLine line) {
        if (line.unitCost() == null || line.unitCost().signum() < 0) {
            throw new Errors.BadRequestException(
                    "cost.invalid", "A cost cannot be negative or missing.");
        }
        Product product =
                products.findWithDetailsById(line.productId())
                        .orElseThrow(
                                () -> Errors.NotFoundException.of("Product", line.productId()));
        BigDecimal rate = pricing.taxRate(product, at);
        BigDecimal netCost =
                costIncludesTax
                        ? MarginCheck.withoutTax(line.unitCost(), rate)
                        : line.unitCost().setScale(4, RoundingMode.HALF_UP);
        PricingService.ResolvedPrice price = pricing.regularPrice(product, branchId, at);
        MarginCheck margin =
                MarginCheck.of(
                        netCost,
                        price.unitPrice().amount(),
                        product.isPriceIncludesTax(),
                        rate,
                        product.effectiveTargetMargin());
        return new CostCheck(
                product.getId(),
                product.getSku(),
                product.getName(),
                line.unitCost(),
                rate,
                price,
                product.isPriceIncludesTax(),
                margin);
    }
}
