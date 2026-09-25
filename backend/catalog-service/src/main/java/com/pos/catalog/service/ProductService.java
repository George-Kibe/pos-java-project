package com.pos.catalog.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Category;
import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.TaxClass;
import com.pos.catalog.domain.UnitOfMeasure;
import com.pos.catalog.messaging.CatalogEventPublisher;
import com.pos.catalog.repository.BrandRepository;
import com.pos.catalog.repository.CategoryRepository;
import com.pos.catalog.repository.ProductRepository;
import com.pos.catalog.repository.TaxClassRepository;
import com.pos.catalog.repository.UnitOfMeasureRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/** Product administration. */
@Service
@RequiredArgsConstructor
public class ProductService {

    private final ProductRepository products;
    private final CategoryRepository categories;
    private final BrandRepository brands;
    private final UnitOfMeasureRepository unitsOfMeasure;
    private final TaxClassRepository taxClasses;
    private final CatalogEventPublisher events;

    @Transactional(readOnly = true)
    public Page<Product> search(String query, UUID categoryId, Pageable pageable) {
        Page<Product> page;
        if (categoryId != null) {
            page = products.findByCategoryId(categoryId, pageable);
        } else if (query == null || query.isBlank()) {
            page = products.findAll(pageable);
        } else {
            page = products.search(query.trim(), pageable);
        }
        page.forEach(ProductService::loadForResponse);
        return page;
    }

    @Transactional(readOnly = true)
    public Product get(UUID id) {
        Product product =
                products.findWithDetailsById(id)
                        .orElseThrow(() -> Errors.NotFoundException.of("Product", id));
        loadForResponse(product);
        return product;
    }

    /**
     * Loads, inside the transaction, what a product response reads after it: the controller maps
     * with no session open, and a lazy collection touched there is a 500.
     */
    private static void loadForResponse(Product product) {
        org.hibernate.Hibernate.initialize(product.getBarcodes());
    }

    @Transactional
    public Product create(NewProduct request) {
        String sku = request.sku().trim().toUpperCase(java.util.Locale.ROOT);
        if (products.existsBySku(sku)) {
            throw new Errors.ConflictException(
                    "product.sku_taken", "A product with SKU %s already exists.".formatted(sku));
        }

        Product product = new Product();
        product.setSku(sku);
        apply(product, request);
        products.save(product);

        // Consumers keep a local copy of product metadata; inventory needs the name and unit,
        // reporting needs the category. Published through the outbox, in this transaction.
        events.productChanged(product);
        return product;
    }

    @Transactional
    public Product update(UUID id, NewProduct request) {
        Product product = get(id);
        BigDecimal previousPrice = product.getBasePrice();

        apply(product, request);
        products.save(product);

        events.productChanged(product);
        if (previousPrice.compareTo(product.getBasePrice()) != 0) {
            // A separate event: a price change matters to services that do not care about a
            // renamed product, and to anyone auditing why a receipt from yesterday differs.
            events.priceChanged(product, previousPrice);
        }
        return product;
    }

    @Transactional
    public Product setActive(UUID id, boolean active) {
        Product product = get(id);
        product.setActive(active);
        products.save(product);
        events.productChanged(product);
        return product;
    }

    @Transactional
    public Product replaceBarcodes(UUID id, List<String> barcodes) {
        Product product = get(id);
        product.getBarcodes().clear();
        if (barcodes != null) {
            for (int i = 0; i < barcodes.size(); i++) {
                String barcode = barcodes.get(i).trim();
                if (!barcode.isEmpty()) {
                    product.addBarcode(barcode, i == 0);
                }
            }
        }
        products.save(product);
        events.productChanged(product);
        return product;
    }

    private void apply(Product product, NewProduct request) {
        Category category =
                categories
                        .findById(request.categoryId())
                        .orElseThrow(
                                () ->
                                        Errors.NotFoundException.of(
                                                "Category", request.categoryId()));
        UnitOfMeasure uom =
                unitsOfMeasure
                        .findById(request.unitOfMeasureId())
                        .orElseThrow(
                                () ->
                                        Errors.NotFoundException.of(
                                                "Unit of measure", request.unitOfMeasureId()));
        TaxClass taxClass =
                taxClasses
                        .findWithRatesById(request.taxClassId())
                        .orElseThrow(
                                () ->
                                        Errors.NotFoundException.of(
                                                "Tax class", request.taxClassId()));

        if (request.sellByWeight() && !uom.isAllowsDecimal()) {
            // Selling by weight in a whole-number unit means the till can only ever ring up 1 kg.
            throw new Errors.BusinessRuleException(
                    "product.weight_needs_decimal_uom",
                    "A product sold by weight needs a unit of measure that allows decimals; %s does not."
                            .formatted(uom.getCode()));
        }

        product.setName(request.name().trim());
        product.setDescription(request.description());
        product.setCategory(category);
        product.setBrand(
                request.brandId() == null
                        ? null
                        : brands.findById(request.brandId())
                                .orElseThrow(
                                        () ->
                                                Errors.NotFoundException.of(
                                                        "Brand", request.brandId())));
        product.setUnitOfMeasure(uom);
        product.setTaxClass(taxClass);
        product.setSellByWeight(request.sellByWeight());
        product.setPriceIncludesTax(request.priceIncludesTax());
        product.setBasePrice(request.basePrice());
        product.setReorderPoint(request.reorderPoint());
        product.setReorderQuantity(request.reorderQuantity());
        // An uploaded picture is managed by ProductImageService; a URL sent with the product's
        // details (the older way, or a stale form) must not replace the one it serves.
        if (product.getImageKey() == null) {
            product.setImageUrl(request.imageUrl());
        }
        if (request.active() != null) {
            product.setActive(request.active());
        }
    }

    /** The fields a caller supplies when creating or updating a product. */
    public record NewProduct(
            String sku,
            String name,
            String description,
            UUID categoryId,
            UUID brandId,
            UUID unitOfMeasureId,
            UUID taxClassId,
            boolean sellByWeight,
            boolean priceIncludesTax,
            BigDecimal basePrice,
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity,
            String imageUrl,
            Boolean active) {}
}
