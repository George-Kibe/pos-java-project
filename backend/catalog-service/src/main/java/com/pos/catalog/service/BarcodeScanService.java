package com.pos.catalog.service;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.barcode.ScaleBarcodeDecoder;
import com.pos.catalog.domain.barcode.ScaleBarcodeRule;
import com.pos.catalog.domain.barcode.ScaleBarcodeScan;
import com.pos.catalog.repository.ProductRepository;
import com.pos.catalog.repository.ScaleBarcodeRuleRepository;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * Turns a scan into a priced line.
 *
 * <p>Ordinary barcodes resolve to a product and a quantity of one. Scale barcodes are read against
 * the configured rules first, because the digits carry the item and either its weight or its price,
 * and treating one as an ordinary barcode would fail to find any product at all.
 */
@Service
@RequiredArgsConstructor
public class BarcodeScanService {

    private final ProductRepository products;
    private final ScaleBarcodeRuleRepository scaleRules;
    private final PricingService pricing;

    @Transactional(readOnly = true)
    public ScanResult scan(String barcode, UUID branchId, boolean member, Instant at) {
        String trimmed = barcode == null ? "" : barcode.trim();
        if (trimmed.isEmpty()) {
            throw new Errors.BadRequestException("barcode.required", "A barcode is required.");
        }

        List<ScaleBarcodeRule> rules = scaleRules.findByActiveTrueOrderByPrefixAsc();
        Optional<ScaleBarcodeScan> scaleScan = ScaleBarcodeDecoder.decode(trimmed, rules);

        if (scaleScan.isPresent()) {
            return fromScale(scaleScan.get(), branchId, member, at);
        }

        Product product =
                products.findByBarcode(trimmed)
                        .orElseThrow(() -> Errors.NotFoundException.of("Barcode", trimmed));

        return new ScanResult(
                trimmed,
                false,
                null,
                BigDecimal.ONE,
                null,
                pricing.resolve(
                        new PricingRequestSpec(
                                product.getId(),
                                null,
                                null,
                                BigDecimal.ONE,
                                branchId,
                                member,
                                at)));
    }

    private ScanResult fromScale(ScaleBarcodeScan scan, UUID branchId, boolean member, Instant at) {

        List<Product> matches = products.findByScaleItemCode(scan.itemCode());
        if (matches.isEmpty()) {
            throw Errors.NotFoundException.of("Scale item", scan.itemCode());
        }
        if (matches.size() > 1) {
            // Resolving this by picking one would charge for the wrong item roughly half the time.
            throw new Errors.ConflictException(
                    "barcode.ambiguous_scale_item",
                    "Scale item code %s matches %d products. Make the codes unique."
                            .formatted(scan.itemCode(), matches.size()));
        }

        Product product = matches.get(0);

        if (scan.carriesWeight()) {
            return new ScanResult(
                    scan.barcode(),
                    true,
                    scan.ruleName(),
                    scan.weight(),
                    null,
                    pricing.resolve(
                            new PricingRequestSpec(
                                    product.getId(),
                                    null,
                                    null,
                                    scan.weight(),
                                    branchId,
                                    member,
                                    at)));
        }

        // Price-embedded: the label already says what to charge for this exact package, so the
        // quantity is one "piece" at that price. Recomputing from a per-kilogram price would
        // disagree with the sticker the customer is holding.
        return new ScanResult(
                scan.barcode(),
                true,
                scan.ruleName(),
                BigDecimal.ONE,
                scan.embeddedPrice(),
                pricing.resolve(
                        new PricingRequestSpec(
                                product.getId(),
                                null,
                                null,
                                BigDecimal.ONE,
                                branchId,
                                member,
                                at)));
    }
}
