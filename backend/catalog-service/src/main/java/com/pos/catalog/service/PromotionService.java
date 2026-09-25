package com.pos.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Promotion;
import com.pos.catalog.domain.PromotionScope;
import com.pos.catalog.domain.PromotionType;
import com.pos.catalog.domain.pricing.PriceBreakdown;
import com.pos.catalog.repository.PromotionRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * Promotions: percentage or amount off, buy X get Y, bundles - each for a window, a branch or all
 * of them, members only or everyone, over products, categories or everything. How they stack is the
 * pricing engine's business ({@code PriceResolver}); this class keeps them well-formed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PromotionService {

    private final PromotionRepository promotions;
    private final PricingService pricing;

    public record Rule(PromotionScope scope, UUID scopeId) {}

    public record Definition(
            String name,
            PromotionType type,
            BigDecimal value,
            BigDecimal buyQuantity,
            BigDecimal getQuantity,
            BigDecimal minQuantity,
            int priority,
            boolean stackable,
            boolean memberOnly,
            UUID branchId,
            Instant validFrom,
            Instant validTo,
            boolean active,
            List<Rule> rules) {}

    public List<Promotion> all() {
        return promotions.findAllByOrderByPriorityAscCodeAsc();
    }

    public Promotion require(UUID id) {
        return promotions
                .findById(id)
                .orElseThrow(() -> Errors.NotFoundException.of("Promotion", id));
    }

    @Transactional
    public Promotion create(String code, Definition definition) {
        String normalised = code.trim().toUpperCase(Locale.ROOT);
        if (promotions.existsByCode(normalised)) {
            throw new Errors.ConflictException(
                    "promotion.code_taken", "A promotion with that code already exists.");
        }
        Promotion promotion =
                new Promotion(normalised, definition.name().trim(), definition.type());
        apply(promotion, definition);
        return promotions.save(promotion);
    }

    /**
     * Rewrites a promotion. The rules are replaced wholesale; they carry no ordinal, so orphan
     * removal's late deletes cannot collide with the new rows.
     */
    @Transactional
    public Promotion update(UUID id, Definition definition) {
        Promotion promotion = require(id);
        promotion.setName(definition.name().trim());
        promotion.setType(definition.type());
        apply(promotion, definition);
        return promotion;
    }

    @Transactional
    public Promotion setActive(UUID id, boolean active) {
        Promotion promotion = require(id);
        promotion.setActive(active);
        return promotion;
    }

    /**
     * What a product would cost with this definition live: the draft is checked as a save would
     * check it, then priced beside every other live promotion. Nothing is stored.
     */
    public PriceBreakdown preview(
            UUID existingId, String code, Definition definition, PricingRequestSpec line) {
        Promotion draft =
                new Promotion(
                        code == null || code.isBlank()
                                ? "PREVIEW"
                                : code.trim().toUpperCase(Locale.ROOT),
                        definition.name() == null ? "Preview" : definition.name().trim(),
                        definition.type());
        apply(draft, definition);
        return pricing.preview(line, draft, existingId);
    }

    private static void apply(Promotion promotion, Definition definition) {
        validate(definition);
        promotion.setValue(definition.value());
        promotion.setBuyQuantity(definition.buyQuantity());
        promotion.setGetQuantity(definition.getQuantity());
        promotion.setMinQuantity(definition.minQuantity());
        promotion.setPriority(definition.priority());
        promotion.setStackable(definition.stackable());
        promotion.setMemberOnly(definition.memberOnly());
        promotion.setBranchId(definition.branchId());
        promotion.setValidFrom(definition.validFrom());
        promotion.setValidTo(definition.validTo());
        promotion.setActive(definition.active());
        promotion.getRules().clear();
        definition
                .rules()
                .forEach(
                        rule ->
                                promotion.addRule(
                                        rule.scope(),
                                        rule.scope() == PromotionScope.ALL
                                                ? null
                                                : rule.scopeId()));
    }

    /** What each type needs, so a promotion that could never apply is refused when it is made. */
    static void validate(Definition definition) {
        if (definition.type() == null) {
            throw invalid("promotion.type_required", "Choose what kind of promotion this is.");
        }
        if (definition.rules() == null || definition.rules().isEmpty()) {
            throw invalid("promotion.rules_required", "Say what the promotion applies to.");
        }
        for (Rule rule : definition.rules()) {
            if (rule.scope() != PromotionScope.ALL && rule.scopeId() == null) {
                throw invalid(
                        "promotion.rule_target_required",
                        "Choose the product or category it applies to.");
            }
        }
        if (definition.validFrom() != null
                && definition.validTo() != null
                && !definition.validTo().isAfter(definition.validFrom())) {
            throw invalid("promotion.invalid_period", "The promotion must end after it starts.");
        }
        switch (definition.type()) {
            case PERCENTAGE_OFF -> {
                // Stored as a fraction: 0.10 is 10% off.
                if (definition.value() == null
                        || definition.value().signum() <= 0
                        || definition.value().compareTo(BigDecimal.ONE) > 0) {
                    throw invalid(
                            "promotion.invalid_percentage",
                            "A percentage off is a fraction above 0 and at most 1 (0.10 is 10%).");
                }
            }
            case AMOUNT_OFF -> {
                if (definition.value() == null || definition.value().signum() <= 0) {
                    throw invalid(
                            "promotion.invalid_amount", "An amount off must be more than zero.");
                }
            }
            case BUY_X_GET_Y -> {
                if (!positive(definition.buyQuantity()) || !positive(definition.getQuantity())) {
                    throw invalid(
                            "promotion.invalid_quantities",
                            "Buy X get Y needs both quantities, each more than zero.");
                }
            }
            case BUNDLE -> {
                if (!positive(definition.minQuantity())
                        || definition.value() == null
                        || definition.value().signum() <= 0) {
                    throw invalid(
                            "promotion.invalid_bundle",
                            "A bundle needs its quantity and its price.");
                }
            }
        }
    }

    private static boolean positive(BigDecimal value) {
        return value != null && value.signum() > 0;
    }

    private static Errors.BadRequestException invalid(String code, String message) {
        return new Errors.BadRequestException(code, message);
    }
}
