package com.pos.catalog.api.dto;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import jakarta.validation.constraints.DecimalMin;
import jakarta.validation.constraints.NotBlank;
import jakarta.validation.constraints.NotNull;
import jakarta.validation.constraints.Positive;
import jakarta.validation.constraints.Size;

import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.ProductBarcode;
import com.pos.catalog.domain.pricing.AppliedDiscount;
import com.pos.catalog.domain.pricing.PriceBreakdown;
import com.pos.catalog.service.ScanResult;

/** Wire types for the catalog API. */
public final class CatalogDtos {

    private CatalogDtos() {}

    // --- products -------------------------------------------------------------

    public record ProductRequest(
            @NotBlank @Size(max = 50) String sku,
            @NotBlank @Size(max = 200) String name,
            String description,
            @NotNull UUID categoryId,
            UUID brandId,
            @NotNull UUID unitOfMeasureId,
            @NotNull UUID taxClassId,
            /*
             * Boxed, not primitive. Jackson 3 enables FAIL_ON_NULL_FOR_PRIMITIVES by default, so
             * a request that simply omits an optional boolean is rejected as unparseable - with a
             * message that does not say which field, which is a miserable thing to debug against
             * someone else's API. Boxed with an explicit default instead.
             */
            Boolean sellByWeight,
            Boolean priceIncludesTax,
            @NotNull @DecimalMin("0.0") BigDecimal basePrice,
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity,
            @Size(max = 500) String imageUrl,
            Boolean active,
            List<String> barcodes) {

        /** Absent means not sold by weight, which is the common case. */
        public boolean sellsByWeight() {
            return Boolean.TRUE.equals(sellByWeight);
        }

        /** Absent means inclusive, matching the shelf-price convention this system assumes. */
        public boolean pricedInclusiveOfTax() {
            return priceIncludesTax == null || priceIncludesTax;
        }
    }

    public record ProductResponse(
            UUID id,
            String sku,
            String name,
            String description,
            UUID categoryId,
            String categoryName,
            UUID brandId,
            String brandName,
            String unitOfMeasure,
            String taxClassCode,
            boolean sellByWeight,
            boolean priceIncludesTax,
            BigDecimal basePrice,
            String currency,
            boolean active,
            List<String> barcodes,
            UUID unitOfMeasureId,
            UUID taxClassId,
            BigDecimal reorderPoint,
            BigDecimal reorderQuantity,
            /** Where the product's image is served, or null; versioned, so it can be cached. */
            String imageUrl) {

        public static ProductResponse from(Product product) {
            return new ProductResponse(
                    product.getId(),
                    product.getSku(),
                    product.getName(),
                    product.getDescription(),
                    product.getCategory().getId(),
                    product.getCategory().getName(),
                    product.getBrand() == null ? null : product.getBrand().getId(),
                    product.getBrand() == null ? null : product.getBrand().getName(),
                    product.getUnitOfMeasure().getCode(),
                    product.getTaxClass().getCode(),
                    product.isSellByWeight(),
                    product.isPriceIncludesTax(),
                    product.getBasePrice(),
                    product.getCurrency(),
                    product.isActive(),
                    product.getBarcodes().stream().map(ProductBarcode::getBarcode).toList(),
                    product.getUnitOfMeasure().getId(),
                    product.getTaxClass().getId(),
                    product.getReorderPoint(),
                    product.getReorderQuantity(),
                    product.getImageUrl());
        }
    }

    public record BarcodesRequest(@NotNull List<String> barcodes) {}

    /**
     * One scale label format. Offsets are zero-based into the 13 digits; the embedded integer is
     * divided by {@code valueDivisor} (grams to kilograms, cents to shillings). The item code is
     * matched against a product's SKU, exactly or as its ending.
     */
    public record ScaleBarcodeRuleResponse(
            String prefix,
            String name,
            int itemCodeStart,
            int itemCodeLength,
            int valueStart,
            int valueLength,
            String embeddedType,
            BigDecimal valueDivisor) {

        public static ScaleBarcodeRuleResponse from(
                com.pos.catalog.domain.barcode.ScaleBarcodeRule rule) {
            return new ScaleBarcodeRuleResponse(
                    rule.getPrefix(),
                    rule.getName(),
                    rule.getItemCodeStart(),
                    rule.getItemCodeLength(),
                    rule.getValueStart(),
                    rule.getValueLength(),
                    rule.getEmbeddedType().name(),
                    rule.getValueDivisor());
        }
    }

    // --- pricing --------------------------------------------------------------

    public record PriceLineRequest(
            UUID productId, String sku, String barcode, @NotNull @Positive BigDecimal quantity) {}

    public record PriceRequest(
            @NotNull List<PriceLineRequest> lines,
            UUID branchId,
            /** Boxed for the same reason as above: omitting it must not fail the whole request. */
            Boolean member,
            /** Prices as at this instant. Omit for now; supply it to reprice history faithfully. */
            Instant at) {

        /** Absent means a walk-in customer, so member-only offers do not apply. */
        public boolean isMember() {
            return Boolean.TRUE.equals(member);
        }
    }

    public record DiscountResponse(
            UUID promotionId, String code, String name, String type, BigDecimal amount) {

        static DiscountResponse from(AppliedDiscount discount) {
            return new DiscountResponse(
                    discount.promotionId(),
                    discount.promotionCode(),
                    discount.promotionName(),
                    discount.type(),
                    discount.amount().amount());
        }
    }

    /**
     * The full working, not just the answer.
     *
     * <p>sales-service snapshots these fields onto the sale line and the receipt prints them, so
     * the shelf price, the till total and the VAT return are all the same figure arrived at once.
     */
    public record PriceResponse(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            BigDecimal unitPrice,
            String priceSource,
            UUID priceListId,
            boolean taxInclusive,
            BigDecimal subtotal,
            List<DiscountResponse> discounts,
            BigDecimal discountTotal,
            BigDecimal discountedSubtotal,
            String taxClassCode,
            BigDecimal taxRate,
            BigDecimal net,
            BigDecimal tax,
            BigDecimal lineTotal,
            String currency) {

        public static PriceResponse from(PriceBreakdown breakdown) {
            return new PriceResponse(
                    breakdown.productId(),
                    breakdown.sku(),
                    breakdown.productName(),
                    breakdown.quantity(),
                    breakdown.unitPrice().amount(),
                    breakdown.priceSource().name(),
                    breakdown.priceListId(),
                    breakdown.taxInclusive(),
                    breakdown.subtotal().amount(),
                    breakdown.discounts().stream().map(DiscountResponse::from).toList(),
                    breakdown.discountTotal().amount(),
                    breakdown.discountedSubtotal().amount(),
                    breakdown.taxClassCode(),
                    breakdown.taxRate(),
                    breakdown.net().amount(),
                    breakdown.tax().amount(),
                    breakdown.lineTotal().amount(),
                    breakdown.lineTotal().currency().getCurrencyCode());
        }
    }

    public record ScanResponse(
            String barcode,
            boolean scaleBarcode,
            String scaleRuleName,
            BigDecimal quantityFromBarcode,
            BigDecimal priceFromBarcode,
            PriceResponse price) {

        public static ScanResponse from(ScanResult result) {
            return new ScanResponse(
                    result.barcode(),
                    result.scaleBarcode(),
                    result.scaleRuleName(),
                    result.quantityFromBarcode(),
                    result.priceFromBarcode(),
                    PriceResponse.from(result.price()));
        }
    }

    // --- reference data -------------------------------------------------------

    public record CategoryRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 150) String name,
            UUID parentId) {}

    public record CategoryResponse(
            UUID id, String code, String name, UUID parentId, boolean active) {}

    public record CategoryUpdateRequest(
            @NotBlank @Size(max = 150) String name, UUID parentId, Boolean active) {
        public boolean isActive() {
            return active == null || active;
        }
    }

    public record BrandRequest(
            @NotBlank @Size(max = 50) String code, @NotBlank @Size(max = 150) String name) {}

    public record BrandUpdateRequest(@NotBlank @Size(max = 150) String name, Boolean active) {
        public boolean isActive() {
            return active == null || active;
        }
    }

    public record BrandResponse(UUID id, String code, String name, boolean active) {}

    public record UnitRequest(
            @NotBlank @Size(max = 20) String code,
            @NotBlank @Size(max = 100) String name,
            Boolean allowsDecimal,
            Integer decimalPlaces) {
        public boolean fractional() {
            return Boolean.TRUE.equals(allowsDecimal);
        }
    }

    public record UnitUpdateRequest(@NotBlank @Size(max = 100) String name) {}

    public record UnitResponse(
            UUID id, String code, String name, boolean allowsDecimal, int decimalPlaces) {}

    public record TaxRateResponse(BigDecimal rate, Instant validFrom, Instant validTo) {}

    public record TaxClassResponse(
            UUID id,
            String code,
            String name,
            String description,
            List<TaxRateResponse> rates,
            boolean active,
            boolean isDefault) {}

    /** A tax class and the rate it starts at: 0.16 for 16%. */
    public record TaxClassRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 150) String name,
            @Size(max = 255) String description,
            @NotNull @DecimalMin("0.0") BigDecimal rate,
            Instant validFrom) {}

    public record TaxClassUpdateRequest(
            @NotBlank @Size(max = 150) String name,
            @Size(max = 255) String description,
            Boolean active) {
        public boolean isActive() {
            return active == null || active;
        }
    }

    /** A new rate from {@code validFrom} (now, if omitted); never in the past. */
    public record TaxRateRequest(@NotNull @DecimalMin("0.0") BigDecimal rate, Instant validFrom) {}

    // --- price lists ----------------------------------------------------------

    public record PriceListRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 150) String name,
            /** Null for a list that applies at every branch. */
            UUID branchId,
            Integer priority,
            Instant validFrom,
            Instant validTo,
            Boolean active) {
        public int priorityOrDefault() {
            return priority == null ? 0 : priority;
        }

        public boolean isActive() {
            return active == null || active;
        }
    }

    public record PriceListResponse(
            UUID id,
            String code,
            String name,
            UUID branchId,
            int priority,
            Instant validFrom,
            Instant validTo,
            boolean active,
            long items) {}

    public record PriceListItemRequest(@NotNull @DecimalMin("0.0") BigDecimal price) {}

    public record PriceListItemResponse(
            UUID productId,
            String sku,
            String productName,
            BigDecimal basePrice,
            BigDecimal price,
            String currency) {}

    // --- promotions -----------------------------------------------------------

    public record PromotionRuleRequest(
            @NotNull com.pos.catalog.domain.PromotionScope scope, UUID scopeId) {}

    /**
     * A promotion. {@code value} is a fraction for PERCENTAGE_OFF (0.10 is 10%), an amount per unit
     * for AMOUNT_OFF, the bundle's price for BUNDLE.
     */
    public record PromotionRequest(
            @NotBlank @Size(max = 50) String code,
            @NotBlank @Size(max = 150) String name,
            @NotNull com.pos.catalog.domain.PromotionType type,
            BigDecimal value,
            BigDecimal buyQuantity,
            BigDecimal getQuantity,
            BigDecimal minQuantity,
            Integer priority,
            Boolean stackable,
            Boolean memberOnly,
            UUID branchId,
            Instant validFrom,
            Instant validTo,
            Boolean active,
            @NotNull @jakarta.validation.Valid List<PromotionRuleRequest> rules) {

        public com.pos.catalog.service.PromotionService.Definition definition() {
            return new com.pos.catalog.service.PromotionService.Definition(
                    name,
                    type,
                    value,
                    buyQuantity,
                    getQuantity,
                    minQuantity,
                    priority == null ? 100 : priority,
                    Boolean.TRUE.equals(stackable),
                    Boolean.TRUE.equals(memberOnly),
                    branchId,
                    validFrom,
                    validTo,
                    active == null || active,
                    rules.stream()
                            .map(
                                    rule ->
                                            new com.pos.catalog.service.PromotionService.Rule(
                                                    rule.scope(), rule.scopeId()))
                            .toList());
        }
    }

    public record PromotionRuleResponse(
            com.pos.catalog.domain.PromotionScope scope, UUID scopeId) {}

    public record PromotionResponse(
            UUID id,
            String code,
            String name,
            com.pos.catalog.domain.PromotionType type,
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
            List<PromotionRuleResponse> rules) {

        public static PromotionResponse from(com.pos.catalog.domain.Promotion promotion) {
            return new PromotionResponse(
                    promotion.getId(),
                    promotion.getCode(),
                    promotion.getName(),
                    promotion.getType(),
                    promotion.getValue(),
                    promotion.getBuyQuantity(),
                    promotion.getGetQuantity(),
                    promotion.getMinQuantity(),
                    promotion.getPriority(),
                    promotion.isStackable(),
                    promotion.isMemberOnly(),
                    promotion.getBranchId(),
                    promotion.getValidFrom(),
                    promotion.getValidTo(),
                    promotion.isActive(),
                    promotion.getRules().stream()
                            .map(
                                    rule ->
                                            new PromotionRuleResponse(
                                                    rule.getScopeType(), rule.getScopeId()))
                            .toList());
        }
    }

    /** A draft (or an edit of {@code promotionId}) priced for one product, nothing saved. */
    public record PromotionPreviewRequest(
            @NotNull @jakarta.validation.Valid PromotionRequest promotion,
            UUID promotionId,
            @NotNull UUID productId,
            @NotNull @Positive BigDecimal quantity,
            UUID branchId,
            Boolean member) {}
}
