package com.pos.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.PriceReview;
import com.pos.catalog.domain.pricing.MarginCheck;
import com.pos.catalog.domain.pricing.PriceSource;
import com.pos.catalog.repository.PriceReviewRepository;
import com.pos.catalog.repository.ProductRepository;
import com.pos.common.error.Errors;
import com.pos.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/**
 * Price reviews: opened when a delivery's cost leaves an item below its target margin (or below
 * cost) at the branch it arrived at, and closed by a manager setting a new price or keeping the old
 * one.
 *
 * <p>A review only proposes. Receiving is never held up by it, and no price moves until someone
 * with {@code price:manage} says so - a cost can be a one-off, and a price is a commercial
 * decision.
 */
@Service
@RequiredArgsConstructor
public class PriceReviewService {

    private static final Logger log = LoggerFactory.getLogger(PriceReviewService.class);

    private final CostCheckService costs;
    private final PriceReviewRepository reviews;
    private final ProductRepository products;
    private final ProductService productService;
    private final PriceListService priceLists;

    /** A posted delivery's lines, landed cost per unit without VAT. */
    public record DeliveredLine(UUID productId, BigDecimal landedUnitCost) {}

    /**
     * Checks a posted delivery at its branch. Each product's newest cost replaces any review still
     * open for it there: the decision is about what the goods cost now.
     *
     * @return the reviews opened
     */
    @Transactional
    public List<PriceReview> checkDelivery(
            UUID receiptId, UUID branchId, Instant receivedAt, List<DeliveredLine> lines) {
        // A product on two lines of one delivery is judged at its dearer cost.
        Map<UUID, BigDecimal> costByProduct = new LinkedHashMap<>();
        for (DeliveredLine line : lines) {
            if (line.landedUnitCost() != null) {
                costByProduct.merge(line.productId(), line.landedUnitCost(), BigDecimal::max);
            }
        }
        List<PriceReview> opened = new java.util.ArrayList<>();
        costByProduct.forEach(
                (productId, cost) -> {
                    if (products.existsById(productId)) {
                        CostCheckService.CostCheck check;
                        try {
                            check =
                                    costs.check(
                                            branchId,
                                            receivedAt,
                                            false,
                                            new CostCheckService.CostLine(productId, cost));
                        } catch (Errors.BusinessRuleException unpriceable) {
                            // A tax class with no rate in force: a catalogue problem to fix, not
                            // a reason to lose the other lines' checks. Thrown by a plain method,
                            // not through a transactional proxy, so nothing is marked for rollback.
                            log.warn(
                                    "Delivery {}: cannot check {}: {}",
                                    receiptId,
                                    productId,
                                    unpriceable.getMessage());
                            return;
                        }
                        supersedeOpen(productId, branchId);
                        if (needsReview(check.margin())) {
                            opened.add(
                                    reviews.save(review(receiptId, branchId, receivedAt, check)));
                        }
                    } else {
                        // Catalog never knew it; nothing to price.
                        log.warn("Delivery {} names unknown product {}", receiptId, productId);
                    }
                });
        return opened;
    }

    @Transactional(readOnly = true)
    public Page<PriceReview> list(UUID branchId, PriceReview.Status status, Pageable pageable) {
        return reviews.findByBranchIdAndStatusOrderByReceivedAtDesc(branchId, status, pageable);
    }

    @Transactional(readOnly = true)
    public PriceReview require(UUID id) {
        return reviews.findWithProductById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Price review", id));
    }

    /**
     * Sets the price and closes the review. The price changed is the one the branch was charging:
     * its price list's when a list set it, the product's base price otherwise.
     *
     * @param price the new price as entered (with VAT when the product's price has it); null takes
     *     the suggestion
     */
    @Transactional
    public PriceReview accept(UUID id, BigDecimal price) {
        PriceReview review = requireOpen(id);
        BigDecimal newPrice = price == null ? review.getSuggestedPrice() : price;
        if (newPrice.signum() <= 0) {
            throw new Errors.BadRequestException(
                    "price_review.invalid_price", "A price must be more than zero.");
        }
        UUID productId = review.getProduct().getId();
        if (review.getPriceSource() == PriceSource.PRICE_LIST && review.getPriceListId() != null) {
            priceLists.setPrice(review.getPriceListId(), productId, newPrice);
        } else {
            productService.setBasePrice(productId, newPrice);
        }
        review.setStatus(PriceReview.Status.ACCEPTED);
        review.setNewPrice(newPrice);
        return decided(review);
    }

    /** Keeps the price as it is, for a reason that stays on the review. */
    @Transactional
    public PriceReview keep(UUID id, String reason) {
        if (reason == null || reason.isBlank()) {
            throw new Errors.BadRequestException(
                    "price_review.reason_required", "Say why the price stays as it is.");
        }
        PriceReview review = requireOpen(id);
        review.setStatus(PriceReview.Status.KEPT);
        review.setReason(reason.strip());
        return decided(review);
    }

    private PriceReview requireOpen(UUID id) {
        PriceReview review = require(id);
        if (!review.isOpen()) {
            throw new Errors.ConflictException(
                    "price_review.closed", "This price review has already been decided.");
        }
        return review;
    }

    private PriceReview decided(PriceReview review) {
        review.setDecidedAt(Instant.now());
        review.setDecidedBy(AuthenticatedUser.currentUserId());
        return reviews.save(review);
    }

    private void supersedeOpen(UUID productId, UUID branchId) {
        reviews.findByProductIdAndBranchIdAndStatus(productId, branchId, PriceReview.Status.OPEN)
                .ifPresent(
                        open -> {
                            open.setStatus(PriceReview.Status.SUPERSEDED);
                            // Flushed before a new one is saved: one open review per product and
                            // branch is a partial unique index, which would refuse the insert.
                            reviews.saveAndFlush(open);
                        });
    }

    private static boolean needsReview(MarginCheck margin) {
        return margin.status() == MarginCheck.Status.BELOW_TARGET
                || margin.status() == MarginCheck.Status.BELOW_COST;
    }

    private PriceReview review(
            UUID receiptId, UUID branchId, Instant receivedAt, CostCheckService.CostCheck check) {
        MarginCheck margin = check.margin();
        PriceReview review = new PriceReview();
        review.setProduct(products.getReferenceById(check.productId()));
        review.setBranchId(branchId);
        review.setReceiptId(receiptId);
        review.setReceivedAt(receivedAt);
        review.setUnitCost(margin.netCost());
        review.setPrice(check.price().unitPrice().amount());
        review.setPriceIncludesTax(check.priceIncludesTax());
        review.setTaxRate(check.taxRate());
        review.setPriceSource(check.price().source());
        review.setPriceListId(check.price().priceListId());
        review.setMargin(margin.margin());
        review.setTargetMargin(margin.targetMargin());
        review.setFinding(margin.status());
        review.setSuggestedPrice(margin.suggestedPrice());
        return review;
    }
}
