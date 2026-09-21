package com.pos.purchasing.service;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.purchasing.config.PurchasingProperties;
import com.pos.purchasing.domain.PurchaseOrder;
import com.pos.purchasing.domain.ReorderSuggestion;
import com.pos.purchasing.domain.SuggestionStatus;
import com.pos.purchasing.domain.SupplierProduct;
import com.pos.purchasing.repository.ReorderSuggestionRepository;
import com.pos.purchasing.repository.SupplierProductRepository;

import lombok.RequiredArgsConstructor;

/**
 * "You are about to run out of this."
 *
 * <p>Driven by inventory's low-stock events rather than by polling inventory, because purchasing
 * does not get to read another service's stock table - and because the moment stock crosses its
 * reorder point is exactly when the suggestion is worth making.
 *
 * <p>An existing open suggestion is sharpened rather than duplicated. A product hovering at its
 * reorder point emits the event repeatedly, and a list that grows a row each time is a list nobody
 * reads.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ReorderService {

    private static final Logger log = LoggerFactory.getLogger(ReorderService.class);

    private final ReorderSuggestionRepository suggestions;
    private final SupplierProductRepository supplierProducts;
    private final PurchasingProperties properties;

    public List<ReorderSuggestion> open(UUID branchId) {
        return suggestions.findByBranchIdAndStatusOrderByCreatedAtDesc(
                branchId, SuggestionStatus.OPEN);
    }

    /**
     * Records or updates a suggestion from a low-stock signal.
     *
     * <p>The quantity suggested is whichever is larger: what inventory asked for, or the preferred
     * supplier's minimum order quantity. Suggesting less than the supplier will ship produces an
     * order that gets rejected at the other end.
     *
     * @return the suggestion, or null when a buyer dismissed this product recently enough that
     *     re-proposing it would be noise
     */
    @Transactional
    public ReorderSuggestion suggest(
            UUID productId,
            UUID branchId,
            String sku,
            String productName,
            BigDecimal quantityOnHand,
            BigDecimal reorderPoint,
            BigDecimal requestedQuantity) {

        if (recentlyDismissed(productId, branchId)) {
            log.debug(
                    "Low stock on {} at branch {} ignored: a buyer dismissed it within the last {}"
                            + " days",
                    sku,
                    branchId,
                    properties.coverDaysOrDefault());
            return null;
        }

        SupplierProduct preferred =
                supplierProducts.findByProductIdAndPreferredTrue(productId).orElse(null);

        BigDecimal quantity = requestedQuantity == null ? BigDecimal.ZERO : requestedQuantity;
        if (preferred != null && quantity.compareTo(preferred.getMinimumOrderQty()) < 0) {
            quantity = preferred.getMinimumOrderQty();
        }
        if (quantity.signum() <= 0) {
            // Nothing useful to propose: inventory knows it is low but not how much to buy, and
            // no supplier is set up to say either.
            quantity = BigDecimal.ONE;
        }

        ReorderSuggestion suggestion =
                suggestions
                        .findByProductIdAndBranchIdAndStatus(
                                productId, branchId, SuggestionStatus.OPEN)
                        .orElseGet(
                                () ->
                                        new ReorderSuggestion(
                                                productId,
                                                branchId,
                                                quantityOnHand,
                                                BigDecimal.ONE));

        suggestion.setSku(sku);
        suggestion.setProductName(productName);
        suggestion.setQuantityOnHand(quantityOnHand);
        suggestion.setReorderPoint(reorderPoint);
        suggestion.setSuggestedQuantity(quantity.setScale(3, RoundingMode.HALF_UP));

        if (preferred != null) {
            suggestion.setSupplier(preferred.getSupplier());
            suggestion.setUnitCost(
                    preferred.getLastUnitCost() != null
                            ? preferred.getLastUnitCost()
                            : preferred.getAgreedUnitCost());
            suggestion.setCurrency(preferred.getCurrency());
        }

        ReorderSuggestion saved = suggestions.save(suggestion);
        log.debug(
                "Reorder suggestion for {} at branch {}: {} on hand, suggest {}",
                sku,
                branchId,
                quantityOnHand,
                saved.getSuggestedQuantity());
        return saved;
    }

    /**
     * Whether a buyer has recently said no to this product at this branch.
     *
     * <p>A cooling-off period rather than a permanent block: stock will genuinely need ordering
     * eventually, and a suppression with no end would turn one "not yet" into never. The window is
     * the same cover period a suggestion is sized for, so the next proposal arrives when the
     * previous decision has had time to be wrong.
     */
    private boolean recentlyDismissed(UUID productId, UUID branchId) {
        Optional<ReorderSuggestion> dismissed =
                suggestions.findFirstByProductIdAndBranchIdAndStatusOrderByUpdatedAtDesc(
                        productId, branchId, SuggestionStatus.DISMISSED);

        return dismissed
                .filter(
                        suggestion ->
                                suggestion
                                        .getUpdatedAt()
                                        .isAfter(
                                                Instant.now()
                                                        .minus(
                                                                Duration.ofDays(
                                                                        properties
                                                                                .coverDaysOrDefault()))))
                .isPresent();
    }

    /** Rejects a suggestion, with a reason, so it is not proposed again for a while. */
    @Transactional
    public ReorderSuggestion dismiss(UUID id, String reason) {
        ReorderSuggestion suggestion =
                suggestions
                        .findById(id)
                        .orElseThrow(() -> Errors.NotFoundException.of("Reorder suggestion", id));

        if (suggestion.getStatus() != SuggestionStatus.OPEN) {
            throw new Errors.ConflictException(
                    "reorder_suggestion.not_open",
                    "Suggestion is %s and cannot be dismissed".formatted(suggestion.getStatus()));
        }
        suggestion.setStatus(SuggestionStatus.DISMISSED);
        suggestion.setDismissedReason(reason);
        return suggestions.save(suggestion);
    }

    /**
     * Marks suggestions as acted on, and records which order acted on them.
     *
     * <p>Keeping the link is what lets a buyer see that a suggestion was dealt with rather than
     * merely disappearing from the list.
     */
    @Transactional
    public void markOrdered(List<UUID> suggestionIds, PurchaseOrder order) {
        for (UUID id : suggestionIds) {
            suggestions
                    .findById(id)
                    .ifPresent(
                            suggestion -> {
                                suggestion.setStatus(SuggestionStatus.ORDERED);
                                suggestion.setPurchaseOrder(order);
                                suggestions.save(suggestion);
                            });
        }
        log.debug(
                "{} suggestions marked ordered against {}",
                suggestionIds.size(),
                order.getOrderNumber());
    }
}
