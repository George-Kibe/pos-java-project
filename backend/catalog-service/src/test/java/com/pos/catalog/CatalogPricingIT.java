package com.pos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.temporal.ChronoUnit;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.catalog.domain.PriceList;
import com.pos.catalog.domain.PriceListItem;
import com.pos.catalog.domain.Product;
import com.pos.catalog.domain.Promotion;
import com.pos.catalog.domain.PromotionScope;
import com.pos.catalog.domain.PromotionType;
import com.pos.catalog.domain.TaxClass;
import com.pos.catalog.domain.TaxRate;
import com.pos.catalog.domain.barcode.Ean13;
import com.pos.catalog.domain.pricing.PriceBreakdown;
import com.pos.catalog.domain.pricing.PriceSource;
import com.pos.catalog.repository.PriceListItemRepository;
import com.pos.catalog.repository.PriceListRepository;
import com.pos.catalog.repository.PromotionRepository;
import com.pos.catalog.service.BarcodeScanService;
import com.pos.catalog.service.PricingRequestSpec;
import com.pos.catalog.service.PricingService;
import com.pos.catalog.service.ProductService;
import com.pos.catalog.service.ScanResult;
import com.pos.common.error.Errors;

/** Pricing end to end: real products, real price lists, real promotions, real tax rates. */
class CatalogPricingIT extends CatalogTestBase {

    @Autowired private PricingService pricing;
    @Autowired private BarcodeScanService scanning;
    @Autowired private ProductService productService;
    @Autowired private PriceListRepository priceLists;
    @Autowired private PriceListItemRepository priceListItems;
    @Autowired private PromotionRepository promotions;

    // --- prices -----------------------------------------------------------------

    @Test
    @DisplayName("with no price list, the product's own price is used")
    void basePriceIsAlwaysAValidAnswer() {
        Product soap = newProduct("SOAP-1", "Bar soap", "STANDARD", "116.00", true);

        PriceBreakdown line = price(soap, "1", null);

        assertThat(line.priceSource()).isEqualTo(PriceSource.BASE_PRICE);
        assertThat(line.unitPrice().amount()).isEqualByComparingTo("116.00");
        assertThat(line.net().amount()).isEqualByComparingTo("100.00");
        assertThat(line.tax().amount()).isEqualByComparingTo("16.00");
    }

    @Test
    @DisplayName("a branch price list overrides the base price for that branch only")
    void branchPriceListOverridesBasePrice() {
        Product soap = newProduct("SOAP-2", "Bar soap", "STANDARD", "116.00", true);
        UUID westlands = UUID.randomUUID();

        PriceList list =
                priceLists.save(new PriceList("WESTLANDS", "Westlands prices", westlands, 10));
        priceListItems.save(new PriceListItem(list, soap, new BigDecimal("99.00")));

        PriceBreakdown atWestlands = price(soap, "1", westlands);
        assertThat(atWestlands.priceSource()).isEqualTo(PriceSource.PRICE_LIST);
        assertThat(atWestlands.unitPrice().amount()).isEqualByComparingTo("99.00");
        assertThat(atWestlands.priceListId()).isEqualTo(list.getId());

        // Another branch is unaffected and falls back to the base price.
        assertThat(price(soap, "1", UUID.randomUUID()).priceSource())
                .isEqualTo(PriceSource.BASE_PRICE);
    }

    @Test
    @DisplayName("the highest-priority price list wins, so two tills agree")
    void highestPriorityPriceListWins() {
        Product soap = newProduct("SOAP-3", "Bar soap", "STANDARD", "116.00", true);
        UUID branch = UUID.randomUUID();

        PriceList chainwide = priceLists.save(new PriceList("CHAIN", "Chain prices", null, 5));
        PriceList promoList = priceLists.save(new PriceList("PROMO", "Promo prices", branch, 50));
        priceListItems.save(new PriceListItem(chainwide, soap, new BigDecimal("110.00")));
        priceListItems.save(new PriceListItem(promoList, soap, new BigDecimal("95.00")));

        assertThat(price(soap, "1", branch).unitPrice().amount()).isEqualByComparingTo("95.00");
    }

    // --- tax over time ----------------------------------------------------------

    @Test
    @DisplayName("a receipt reprinted from before a VAT change uses the rate of the day")
    void taxIsResolvedAsAtTheInstant() {
        // Its own tax class: changing the seeded one would leave every other test with no rate.
        TaxClass vat = newTaxClass("VAT_CHANGE", "0.160000");
        Product soap = newProductWithTaxClass("SOAP-4", "Bar soap", vat, "116.00", true);

        // VAT drops to 8% from a known date; the old row is closed, not edited.
        Instant change = Instant.parse("2026-07-01T00:00:00Z");
        vat.getRates().get(0).setValidTo(change);
        vat.getRates().add(new TaxRate(vat, new BigDecimal("0.080000"), change));
        taxClasses.save(vat);

        PriceBreakdown before = priceAt(soap, change.minus(1, ChronoUnit.DAYS));
        PriceBreakdown after = priceAt(soap, change.plus(1, ChronoUnit.DAYS));

        assertThat(before.taxRate()).isEqualByComparingTo("0.160000");
        assertThat(before.tax().amount()).isEqualByComparingTo("16.00");

        assertThat(after.taxRate()).isEqualByComparingTo("0.080000");
        assertThat(after.tax().amount()).isEqualByComparingTo("8.59");

        // The customer pays the same inclusive price either way; only the split changes.
        assertThat(before.lineTotal()).isEqualByComparingTo(after.lineTotal());
    }

    @Test
    @DisplayName("a tax class with no rate in force fails loudly rather than charging nothing")
    void aMissingRateIsAnError() {
        TaxClass lapsed = newTaxClass("LAPSED", "0.160000");
        Product soap = newProductWithTaxClass("SOAP-5", "Bar soap", lapsed, "116.00", true);

        lapsed.getRates().forEach(rate -> rate.setValidTo(Instant.parse("2020-01-01T00:00:00Z")));
        taxClasses.save(lapsed);

        // Defaulting to zero here is exactly the failure a tax authority notices.
        assertThatThrownBy(() -> price(soap, "1", null))
                .isInstanceOf(Errors.BusinessRuleException.class)
                .hasMessageContaining("no rate in force");
    }

    // --- promotions -------------------------------------------------------------

    @Test
    @DisplayName("a category promotion applies to every product in that category")
    void categoryPromotionApplies() {
        Product soap = newProduct("SOAP-6", "Bar soap", "STANDARD", "200.00", true);

        Promotion tenOff =
                new Promotion("TEN_OFF", "10% off groceries", PromotionType.PERCENTAGE_OFF);
        tenOff.setValue(new BigDecimal("0.10"));
        tenOff.setStackable(true);
        tenOff.addRule(PromotionScope.CATEGORY, soap.getCategory().getId());
        promotions.save(tenOff);

        PriceBreakdown line = price(soap, "1", null);

        assertThat(line.discounts()).hasSize(1);
        assertThat(line.discounts().get(0).promotionCode()).isEqualTo("TEN_OFF");
        assertThat(line.lineTotal().amount()).isEqualByComparingTo("180.00");
    }

    @Test
    @DisplayName("a member-only offer does not apply to a walk-in customer")
    void memberOnlyOffersRequireAMember() {
        Product soap = newProduct("SOAP-7", "Bar soap", "STANDARD", "200.00", true);

        Promotion memberOffer =
                new Promotion("MEMBER15", "Members save 15%", PromotionType.PERCENTAGE_OFF);
        memberOffer.setValue(new BigDecimal("0.15"));
        memberOffer.setStackable(true);
        memberOffer.setMemberOnly(true);
        memberOffer.addRule(PromotionScope.PRODUCT, soap.getId());
        promotions.save(memberOffer);

        assertThat(priceFor(soap, "1", null, false).discounts()).isEmpty();
        assertThat(priceFor(soap, "1", null, true).lineTotal().amount())
                .isEqualByComparingTo("170.00");
    }

    @Test
    @DisplayName("a promotion outside its window does not apply")
    void expiredPromotionsDoNotApply() {
        Product soap = newProduct("SOAP-8", "Bar soap", "STANDARD", "200.00", true);

        Promotion lastWeek = new Promotion("EASTER", "Easter offer", PromotionType.PERCENTAGE_OFF);
        lastWeek.setValue(new BigDecimal("0.25"));
        lastWeek.setStackable(true);
        lastWeek.setValidFrom(Instant.parse("2026-04-01T00:00:00Z"));
        lastWeek.setValidTo(Instant.parse("2026-04-10T00:00:00Z"));
        lastWeek.addRule(PromotionScope.PRODUCT, soap.getId());
        promotions.save(lastWeek);

        assertThat(priceAt(soap, Instant.parse("2026-04-05T00:00:00Z")).discounts()).hasSize(1);
        assertThat(priceAt(soap, Instant.parse("2026-05-05T00:00:00Z")).discounts()).isEmpty();
    }

    @Test
    @DisplayName("a promotion for another branch does not apply here")
    void branchScopedPromotionsStayInTheirBranch() {
        Product soap = newProduct("SOAP-9", "Bar soap", "STANDARD", "200.00", true);
        UUID otherBranch = UUID.randomUUID();

        Promotion elsewhere =
                new Promotion("CBD_ONLY", "CBD branch offer", PromotionType.PERCENTAGE_OFF);
        elsewhere.setValue(new BigDecimal("0.20"));
        elsewhere.setStackable(true);
        elsewhere.setBranchId(otherBranch);
        elsewhere.addRule(PromotionScope.PRODUCT, soap.getId());
        promotions.save(elsewhere);

        assertThat(price(soap, "1", UUID.randomUUID()).discounts()).isEmpty();
        assertThat(price(soap, "1", otherBranch).discounts()).hasSize(1);
    }

    // --- scanning ---------------------------------------------------------------

    @Test
    @DisplayName("an ordinary barcode resolves to its product at quantity one")
    void ordinaryBarcodeScan() {
        Product soap = newProduct("SOAP-10", "Bar soap", "STANDARD", "116.00", true);
        productService.replaceBarcodes(soap.getId(), List.of("5060001234567"));

        ScanResult result = scanning.scan("5060001234567", null, false, null);

        assertThat(result.scaleBarcode()).isFalse();
        assertThat(result.quantityFromBarcode()).isEqualByComparingTo("1");
        assertThat(result.price().sku()).isEqualTo("SOAP-10");
        assertThat(result.price().lineTotal().amount()).isEqualByComparingTo("116.00");
    }

    @Test
    @DisplayName("a scale barcode resolves to the right product priced at the weight on the label")
    void scaleBarcodeResolvesProductAndWeight() {
        // Tomatoes at 250.00 per kilogram, item code 12345.
        Product tomatoes =
                newProduct("12345", "Tomatoes", "ZERO_RATED", "250.00", true, "KG", true);

        // 20 | 12345 | 01235 | check  ->  item 12345, 1.235 kg
        String barcode = Ean13.withCheckDigit("201234501235");
        ScanResult result = scanning.scan(barcode, null, false, null);

        assertThat(result.scaleBarcode()).isTrue();
        assertThat(result.quantityFromBarcode()).isEqualByComparingTo("1.235");
        assertThat(result.price().productId()).isEqualTo(tomatoes.getId());
        // 1.235 kg x 250.00
        assertThat(result.price().lineTotal().amount()).isEqualByComparingTo("308.75");
        assertThat(result.price().tax().isZero()).isTrue();
    }

    @Test
    @DisplayName("an ambiguous scale item code is refused rather than charging for the wrong item")
    void ambiguousScaleItemCodeIsRefused() {
        newProduct("12345", "Tomatoes", "ZERO_RATED", "250.00", true, "KG", true);
        newProduct("X-12345", "Cherry tomatoes", "ZERO_RATED", "450.00", true, "KG", true);

        String barcode = Ean13.withCheckDigit("201234501235");

        assertThatThrownBy(() -> scanning.scan(barcode, null, false, null))
                .isInstanceOf(Errors.ConflictException.class)
                .hasMessageContaining("matches 2 products");
    }

    @Test
    void anUnknownBarcodeIsNotFound() {
        assertThatThrownBy(() -> scanning.scan("5060009999999", null, false, null))
                .isInstanceOf(Errors.NotFoundException.class);
    }

    // --- events -----------------------------------------------------------------

    @Test
    @DisplayName("creating a product queues a product-changed event in the same transaction")
    void productChangesAreAnnounced() {
        var created =
                productService.create(
                        new ProductService.NewProduct(
                                "EVENT-1",
                                "Announced product",
                                null,
                                categories.findByCode("GROCERY").orElseThrow().getId(),
                                null,
                                unitsOfMeasure.findByCode("EA").orElseThrow().getId(),
                                taxClasses.findWithRatesByCode("STANDARD").orElseThrow().getId(),
                                false,
                                true,
                                new BigDecimal("116.00"),
                                null,
                                null,
                                null,
                                true));

        String payload =
                jdbc.sql(
                                """
                                SELECT payload FROM catalog.outbox
                                WHERE topic = 'pos.catalog.product-changed.v1'
                                ORDER BY created_at DESC LIMIT 1
                                """)
                        .query(String.class)
                        .single();

        assertThat(payload).contains("EVENT-1").contains("Announced product").contains("STANDARD");
        assertThat(created.getSku()).isEqualTo("EVENT-1");
    }

    @Test
    @DisplayName("changing a price announces it separately, with the old and new figures")
    void priceChangesAreAnnouncedSeparately() {
        Product soap = newProduct("PRICE-1", "Bar soap", "STANDARD", "116.00", true);

        productService.update(
                soap.getId(),
                new ProductService.NewProduct(
                        "PRICE-1",
                        "Bar soap",
                        null,
                        soap.getCategory().getId(),
                        null,
                        soap.getUnitOfMeasure().getId(),
                        soap.getTaxClass().getId(),
                        false,
                        true,
                        new BigDecimal("130.00"),
                        null,
                        null,
                        null,
                        true));

        Long priceEvents =
                jdbc.sql(
                                """
                                SELECT count(*) FROM catalog.outbox
                                WHERE topic = 'pos.catalog.price-changed.v1'
                                """)
                        .query(Long.class)
                        .single();
        assertThat(priceEvents).isEqualTo(1);

        String payload =
                jdbc.sql(
                                """
                                SELECT payload FROM catalog.outbox
                                WHERE topic = 'pos.catalog.price-changed.v1' LIMIT 1
                                """)
                        .query(String.class)
                        .single();
        // Both figures, so "why is yesterday's receipt different" has an answer on record.
        // Compared as numbers: the scale a BigDecimal happens to serialise with is not the point.
        var node = com.pos.events.EventJson.mapper().readTree(payload).get("payload");
        assertThat(new BigDecimal(node.get("previousPrice").asString()))
                .isEqualByComparingTo("116.00");
        assertThat(new BigDecimal(node.get("newPrice").asString())).isEqualByComparingTo("130.00");
    }

    // --- helpers ----------------------------------------------------------------

    private PriceBreakdown price(Product product, String quantity, UUID branchId) {
        return priceFor(product, quantity, branchId, false);
    }

    private PriceBreakdown priceFor(
            Product product, String quantity, UUID branchId, boolean member) {
        return pricing.resolve(
                new PricingRequestSpec(
                        product.getId(),
                        null,
                        null,
                        new BigDecimal(quantity),
                        branchId,
                        member,
                        null));
    }

    private PriceBreakdown priceAt(Product product, Instant at) {
        return pricing.resolve(
                new PricingRequestSpec(
                        product.getId(), null, null, BigDecimal.ONE, null, false, at));
    }
}
