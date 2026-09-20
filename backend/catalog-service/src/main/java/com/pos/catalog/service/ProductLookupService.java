package com.pos.catalog.service;

import java.util.Optional;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Product;
import com.pos.catalog.repository.ProductRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/** Finds a product by whichever identifier the caller happens to have. */
@Service
@RequiredArgsConstructor
public class ProductLookupService {

    private final ProductRepository products;

    @Transactional(readOnly = true)
    public Product require(PricingRequestSpec spec) {
        return find(spec)
                .orElseThrow(
                        () ->
                                Errors.NotFoundException.of(
                                        "Product",
                                        firstNonBlank(
                                                spec.productId() == null
                                                        ? null
                                                        : spec.productId().toString(),
                                                spec.sku(),
                                                spec.barcode())));
    }

    @Transactional(readOnly = true)
    public Optional<Product> find(PricingRequestSpec spec) {
        if (spec.productId() != null) {
            return products.findWithDetailsById(spec.productId());
        }
        if (spec.sku() != null && !spec.sku().isBlank()) {
            return products.findBySku(spec.sku().trim());
        }
        if (spec.barcode() != null && !spec.barcode().isBlank()) {
            return products.findByBarcode(spec.barcode().trim());
        }
        throw new Errors.BadRequestException(
                "product.identifier_required", "Give a product id, a SKU or a barcode.");
    }

    private static String firstNonBlank(String... values) {
        for (String value : values) {
            if (value != null && !value.isBlank()) {
                return value;
            }
        }
        return "unknown";
    }
}
