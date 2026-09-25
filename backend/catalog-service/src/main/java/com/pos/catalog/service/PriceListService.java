package com.pos.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Locale;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.PriceList;
import com.pos.catalog.domain.PriceListItem;
import com.pos.catalog.domain.Product;
import com.pos.catalog.messaging.CatalogEventPublisher;
import com.pos.catalog.repository.PriceListItemRepository;
import com.pos.catalog.repository.PriceListRepository;
import com.pos.catalog.repository.ProductRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * Price lists: a branch's (or the whole group's) own prices for the products where they differ from
 * the base price. Pricing picks the highest-priority list in force that names the product, and
 * falls back to the base price - so a list only needs rows where the price actually differs.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class PriceListService {

    private final PriceListRepository lists;
    private final PriceListItemRepository items;
    private final ProductRepository products;
    private final CatalogEventPublisher events;

    public record Definition(
            String name,
            UUID branchId,
            int priority,
            Instant validFrom,
            Instant validTo,
            boolean active) {}

    public List<PriceList> all() {
        return lists.findAll().stream()
                .sorted(
                        java.util.Comparator.comparingInt(PriceList::getPriority)
                                .reversed()
                                .thenComparing(PriceList::getCode))
                .toList();
    }

    public PriceList require(UUID id) {
        return lists.findById(id).orElseThrow(() -> Errors.NotFoundException.of("Price list", id));
    }

    public Page<PriceListItem> items(UUID listId, Pageable pageable) {
        require(listId);
        return items.findWithProductByPriceListId(listId, pageable);
    }

    public long itemCount(UUID listId) {
        return items.countByPriceListId(listId);
    }

    @Transactional
    public PriceList create(String code, Definition definition) {
        String normalised = code.trim().toUpperCase(Locale.ROOT);
        if (lists.existsByCode(normalised)) {
            throw new Errors.ConflictException(
                    "price_list.code_taken", "A price list with that code already exists.");
        }
        PriceList list =
                new PriceList(
                        normalised,
                        definition.name().trim(),
                        definition.branchId(),
                        definition.priority());
        apply(list, definition);
        return lists.save(list);
    }

    @Transactional
    public PriceList update(UUID id, Definition definition) {
        PriceList list = require(id);
        list.setName(definition.name().trim());
        list.setBranchId(definition.branchId());
        list.setPriority(definition.priority());
        apply(list, definition);
        return list;
    }

    /** Sets a product's price on the list, and announces the change. */
    @Transactional
    public PriceListItem setPrice(UUID listId, UUID productId, BigDecimal price) {
        if (price == null || price.signum() < 0) {
            throw new Errors.BadRequestException(
                    "price_list.invalid_price", "A price cannot be negative.");
        }
        PriceList list = require(listId);
        Product product =
                products.findById(productId)
                        .orElseThrow(() -> Errors.NotFoundException.of("Product", productId));
        var existing = items.findByPriceListIdAndProductId(listId, productId);
        PriceListItem item = existing.orElseGet(() -> new PriceListItem(list, product, price));
        // New to the list, it was selling at its base price until now. (Not "item has no id":
        // every entity gets its id when it is constructed.)
        BigDecimal previous = existing.map(PriceListItem::getPrice).orElse(product.getBasePrice());
        item.setPrice(price);
        PriceListItem saved = items.save(item);
        events.priceChanged(product, list.getBranchId(), previous, price);
        return saved;
    }

    /** Takes a product off the list: it goes back to the base price, or a lower-priority list. */
    @Transactional
    public void removePrice(UUID listId, UUID productId) {
        PriceList list = require(listId);
        PriceListItem item =
                items.findByPriceListIdAndProductId(listId, productId)
                        .orElseThrow(
                                () ->
                                        new Errors.NotFoundException(
                                                "price_list.no_price",
                                                "That product has no price on this list."));
        Product product = item.getProduct();
        BigDecimal previous = item.getPrice();
        items.delete(item);
        events.priceChanged(product, list.getBranchId(), previous, product.getBasePrice());
    }

    private static void apply(PriceList list, Definition definition) {
        if (definition.validFrom() != null
                && definition.validTo() != null
                && !definition.validTo().isAfter(definition.validFrom())) {
            throw new Errors.BadRequestException(
                    "price_list.invalid_period", "The list must end after it starts.");
        }
        list.setValidFrom(definition.validFrom());
        list.setValidTo(definition.validTo());
        list.setActive(definition.active());
    }
}
