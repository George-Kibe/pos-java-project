package com.pos.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.ArrayList;
import java.util.Comparator;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.PriceList;
import com.pos.catalog.domain.PriceListItem;
import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.Promotion;
import com.pos.catalog.domain.TaxRate;
import com.pos.catalog.domain.pricing.PriceBreakdown;
import com.pos.catalog.domain.pricing.PriceResolver;
import com.pos.catalog.domain.pricing.PriceSource;
import com.pos.catalog.domain.pricing.PricingRequest;
import com.pos.catalog.domain.pricing.PromotionCandidate;
import com.pos.catalog.repository.PriceListItemRepository;
import com.pos.catalog.repository.PriceListRepository;
import com.pos.catalog.repository.PromotionRepository;
import com.pos.common.error.Errors;
import com.pos.common.money.Money;

import lombok.RequiredArgsConstructor;

/**
 * Turns a product and a quantity into a priced line.
 *
 * <p>Loads what the pure {@link PriceResolver} needs and hands it over. The arithmetic lives in the
 * resolver so it can be tested exhaustively without a database; this class is only responsible for
 * finding the right inputs.
 */
@Service
@RequiredArgsConstructor
public class PricingService {

    private final ProductLookupService products;
    private final PriceListRepository priceLists;
    private final PriceListItemRepository priceListItems;
    private final PromotionRepository promotions;

    @Transactional(readOnly = true)
    public PriceBreakdown resolve(PricingRequestSpec spec) {
        Product product = products.require(spec);
        return priceFor(product, spec);
    }

    /**
     * Prices several lines at once.
     *
     * <p>A basket is priced in one call so that every line sees the same instant. Pricing them one
     * at a time across a tax-rate change at midnight would put two different rates on one receipt.
     */
    @Transactional(readOnly = true)
    public List<PriceBreakdown> resolveAll(List<PricingRequestSpec> specs) {
        Instant at = specs.isEmpty() ? Instant.now() : specs.get(0).effectiveAt();
        List<PriceBreakdown> breakdowns = new ArrayList<>(specs.size());
        for (PricingRequestSpec spec : specs) {
            Product product = products.require(spec);
            breakdowns.add(
                    priceFor(
                            product,
                            new PricingRequestSpec(
                                    spec.productId(),
                                    spec.sku(),
                                    spec.barcode(),
                                    spec.quantity(),
                                    spec.branchId(),
                                    spec.member(),
                                    at)));
        }
        return breakdowns;
    }

    /**
     * Prices one line as it would be with {@code draft} live - a promotion not saved yet, or an
     * edit of one that is - so the promotions builder shows the price before anyone commits to it.
     * The draft takes the saved version's place; nothing is written.
     */
    @Transactional(readOnly = true)
    public PriceBreakdown preview(PricingRequestSpec spec, Promotion draft, UUID replacing) {
        Product product = products.require(spec);
        return priceFor(product, spec, draft, replacing);
    }

    private PriceBreakdown priceFor(Product product, PricingRequestSpec spec) {
        return priceFor(product, spec, null, null);
    }

    private PriceBreakdown priceFor(
            Product product, PricingRequestSpec spec, Promotion draft, UUID replacing) {
        Instant at = spec.effectiveAt();

        ResolvedPrice price = resolveUnitPrice(product, spec.branchId(), at);
        BigDecimal taxRate = resolveTaxRate(product, at);
        List<PromotionCandidate> candidates =
                candidatesFor(product, spec.branchId(), spec.member(), at, draft, replacing);

        PricingRequest request =
                new PricingRequest(
                        product.getId(),
                        product.getSku(),
                        product.getName(),
                        spec.quantity(),
                        price.unitPrice(),
                        price.source(),
                        price.priceListId(),
                        product.isPriceIncludesTax(),
                        product.getTaxClass().getCode(),
                        taxRate,
                        candidates);

        return PriceResolver.resolve(request);
    }

    /** An item's everyday price at a branch - before promotions - and where it came from. */
    public record ResolvedPrice(Money unitPrice, PriceSource source, UUID priceListId) {}

    /**
     * The price a branch charges before any promotion: its price list's, or the product's own. What
     * a delivery's cost is judged against.
     */
    @Transactional(readOnly = true)
    public ResolvedPrice regularPrice(Product product, UUID branchId, Instant at) {
        return resolveUnitPrice(product, branchId, at);
    }

    /** The product's tax rate at {@code at}. */
    public BigDecimal taxRate(Product product, Instant at) {
        return resolveTaxRate(product, at);
    }

    /**
     * The branch price if there is one, otherwise the product's own.
     *
     * <p>Base price is always a valid answer, so a missing price list entry is not an error and a
     * branch only needs rows where it genuinely differs from the chain.
     */
    private ResolvedPrice resolveUnitPrice(Product product, UUID branchId, Instant at) {
        List<PriceList> applicable = applicablePriceLists(branchId, at);
        if (applicable.isEmpty()) {
            return new ResolvedPrice(product.basePriceAsMoney(), PriceSource.BASE_PRICE, null);
        }

        List<UUID> ids = applicable.stream().map(PriceList::getId).toList();
        List<PriceListItem> items = priceListItems.findForProductInLists(product.getId(), ids);
        if (items.isEmpty()) {
            return new ResolvedPrice(product.basePriceAsMoney(), PriceSource.BASE_PRICE, null);
        }

        // Highest priority wins; the lists arrive already ordered, so position in that list
        // decides.
        Optional<PriceListItem> best =
                items.stream()
                        .min(
                                Comparator.comparingInt(
                                        item -> ids.indexOf(item.getPriceList().getId())));

        return best.map(
                        item ->
                                new ResolvedPrice(
                                        item.priceAsMoney(),
                                        PriceSource.PRICE_LIST,
                                        item.getPriceList().getId()))
                .orElseGet(
                        () ->
                                new ResolvedPrice(
                                        product.basePriceAsMoney(), PriceSource.BASE_PRICE, null));
    }

    private List<PriceList> applicablePriceLists(UUID branchId, Instant at) {
        List<PriceList> lists = new ArrayList<>();
        if (branchId != null) {
            lists.addAll(
                    priceLists.findByActiveTrueAndBranchIdInOrderByPriorityDescCodeAsc(
                            List.of(branchId)));
        }
        lists.addAll(priceLists.findByActiveTrueAndBranchIdIsNullOrderByPriorityDescCodeAsc());

        return lists.stream()
                .filter(list -> list.appliesAt(at))
                .sorted(
                        Comparator.comparingInt(PriceList::getPriority)
                                .reversed()
                                .thenComparing(PriceList::getCode))
                .toList();
    }

    /**
     * The rate in force at {@code at}.
     *
     * <p>A class with no rate covering the instant is a configuration error, not a reason to
     * default to zero: silently charging no tax is exactly the failure a tax authority notices.
     */
    private BigDecimal resolveTaxRate(Product product, Instant at) {
        return product.getTaxClass()
                .rateAt(at)
                .map(TaxRate::getRate)
                .orElseThrow(
                        () ->
                                new Errors.BusinessRuleException(
                                        "tax.no_rate_in_force",
                                        "Tax class %s has no rate in force at %s"
                                                .formatted(product.getTaxClass().getCode(), at)));
    }

    private List<PromotionCandidate> candidatesFor(
            Product product,
            UUID branchId,
            boolean member,
            Instant at,
            Promotion draft,
            UUID replacing) {

        List<Promotion> live =
                new ArrayList<>(promotions.findByActiveTrueOrderByPriorityAscCodeAsc());
        if (draft != null) {
            live.removeIf(
                    promotion ->
                            promotion.getId().equals(replacing)
                                    || promotion.getCode().equals(draft.getCode()));
            live.add(draft);
            live.sort(
                    Comparator.comparingInt(Promotion::getPriority)
                            .thenComparing(Promotion::getCode));
        }
        return live.stream()
                .filter(promotion -> promotion.runsAt(at))
                .filter(promotion -> promotion.appliesToBranch(branchId))
                // A member-only offer must not apply to a walk-in customer, and the till knows
                // whether a member was attached before the basket is priced.
                .filter(promotion -> !promotion.isMemberOnly() || member)
                .filter(
                        promotion ->
                                promotion.covers(product.getId(), product.getCategory().getId()))
                .map(PricingService::toCandidate)
                .toList();
    }

    private static PromotionCandidate toCandidate(Promotion promotion) {
        return new PromotionCandidate(
                promotion.getId(),
                promotion.getCode(),
                promotion.getName(),
                promotion.getType(),
                promotion.getValue(),
                promotion.getBuyQuantity(),
                promotion.getGetQuantity(),
                promotion.getMinQuantity(),
                promotion.getPriority(),
                promotion.isStackable());
    }
}
