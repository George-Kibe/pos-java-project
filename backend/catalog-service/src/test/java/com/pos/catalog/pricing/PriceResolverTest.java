package com.pos.catalog.pricing;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.pos.catalog.domain.PromotionType;
import com.pos.catalog.domain.pricing.PriceBreakdown;
import com.pos.catalog.domain.pricing.PriceResolver;
import com.pos.catalog.domain.pricing.PriceSource;
import com.pos.catalog.domain.pricing.PricingRequest;
import com.pos.catalog.domain.pricing.PromotionCandidate;
import com.pos.common.money.Money;

/**
 * Every combination of tax treatment, quantity and promotion.
 *
 * <p>Exhaustive on purpose. Pricing is where a mistake is discovered by a customer at a till rather
 * than by a developer, and where being wrong by a few cents on every line adds up to real money by
 * the end of a trading day.
 */
class PriceResolverTest {

    private static final BigDecimal VAT_16 = new BigDecimal("0.16");
    private static final BigDecimal ZERO_RATE = BigDecimal.ZERO;

    // --- tax treatments -------------------------------------------------------

    @Nested
    class TaxTreatment {

        @Test
        @DisplayName("an inclusive price is what the customer pays; tax comes out of it")
        void inclusiveStandardRated() {
            PriceBreakdown line = resolve(request("116.00", "1", true, VAT_16, "STANDARD"));

            assertThat(line.subtotal().amount()).isEqualByComparingTo("116.00");
            assertThat(line.net().amount()).isEqualByComparingTo("100.00");
            assertThat(line.tax().amount()).isEqualByComparingTo("16.00");
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("116.00");
        }

        @Test
        @DisplayName(
                "an exclusive price has tax added, so the customer pays more than the subtotal")
        void exclusiveStandardRated() {
            PriceBreakdown line = resolve(request("100.00", "1", false, VAT_16, "STANDARD"));

            assertThat(line.subtotal().amount()).isEqualByComparingTo("100.00");
            assertThat(line.net().amount()).isEqualByComparingTo("100.00");
            assertThat(line.tax().amount()).isEqualByComparingTo("16.00");
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("116.00");
        }

        @Test
        @DisplayName("bread and milk are zero rated: no tax either way")
        void zeroRated() {
            PriceBreakdown inclusive =
                    resolve(request("60.00", "2", true, ZERO_RATE, "ZERO_RATED"));
            assertThat(inclusive.tax().isZero()).isTrue();
            assertThat(inclusive.lineTotal().amount()).isEqualByComparingTo("120.00");
            assertThat(inclusive.net().amount()).isEqualByComparingTo("120.00");

            PriceBreakdown exclusive =
                    resolve(request("60.00", "2", false, ZERO_RATE, "ZERO_RATED"));
            assertThat(exclusive.lineTotal().amount()).isEqualByComparingTo("120.00");
        }

        @Test
        @DisplayName("a mixed basket taxes each line by its own class")
        void aMixedBasketIsTaxedPerLine() {
            PriceBreakdown bread = resolve(request("55.00", "1", true, ZERO_RATE, "ZERO_RATED"));
            PriceBreakdown soap = resolve(request("232.00", "1", true, VAT_16, "STANDARD"));

            assertThat(bread.tax().isZero()).isTrue();
            assertThat(soap.tax().amount()).isEqualByComparingTo("32.00");

            Money basketTotal = bread.lineTotal().add(soap.lineTotal());
            Money basketTax = bread.tax().add(soap.tax());
            assertThat(basketTotal.amount()).isEqualByComparingTo("287.00");
            assertThat(basketTax.amount()).isEqualByComparingTo("32.00");
        }
    }

    // --- quantities -----------------------------------------------------------

    @Nested
    class Quantities {

        @Test
        @DisplayName("a weighed line multiplies a fractional quantity by the unit price")
        void weighedGoods() {
            // 1.235 kg of tomatoes at 250.00/kg
            PriceBreakdown line = resolve(request("250.00", "1.235", true, VAT_16, "STANDARD"));

            assertThat(line.subtotal().amount()).isEqualByComparingTo("308.75");
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("308.75");
            assertThat(line.net().add(line.tax()).amount()).isEqualByComparingTo("308.75");
        }

        @Test
        @DisplayName("a weight that does not divide evenly still reconciles exactly")
        void awkwardWeightsStillReconcile() {
            PriceBreakdown line = resolve(request("333.33", "0.777", true, VAT_16, "STANDARD"));

            assertThat(line.net().add(line.tax())).isEqualByComparingTo(line.lineTotal());
            assertThat(line.subtotal().amount().scale()).isLessThanOrEqualTo(4);
        }

        @Test
        void quantityMustBePositive() {
            assertThatThrownBy(() -> resolve(request("100.00", "0", true, VAT_16, "STANDARD")))
                    .isInstanceOf(IllegalArgumentException.class)
                    .hasMessageContaining("Quantity must be positive");
            assertThatThrownBy(() -> resolve(request("100.00", "-1", true, VAT_16, "STANDARD")))
                    .isInstanceOf(IllegalArgumentException.class);
        }
    }

    // --- promotions -----------------------------------------------------------

    @Nested
    class Promotions {

        @Test
        @DisplayName("a percentage off reduces the line before tax is worked out")
        void percentageOff() {
            PriceBreakdown line =
                    resolve(
                            request("200.00", "1", true, VAT_16, "STANDARD")
                                    .withPromotions(percentage("TEN", "0.10", 10, true)));

            assertThat(line.discountTotal().amount()).isEqualByComparingTo("20.00");
            assertThat(line.discountedSubtotal().amount()).isEqualByComparingTo("180.00");
            // Tax follows the discount. Taxing 200 and then discounting would overcharge.
            assertThat(line.tax().amount()).isEqualByComparingTo("24.83");
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("180.00");
        }

        @Test
        void amountOffAppliesPerUnit() {
            PriceBreakdown line =
                    resolve(
                            request("100.00", "3", true, VAT_16, "STANDARD")
                                    .withPromotions(amount("FIVE_OFF", "5.00", 10, true)));

            assertThat(line.subtotal().amount()).isEqualByComparingTo("300.00");
            assertThat(line.discountTotal().amount()).isEqualByComparingTo("15.00");
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("285.00");
        }

        @Test
        @DisplayName("three for two gives one free unit per complete group, and no part groups")
        void buyTwoGetOneFree() {
            PromotionCandidate threeForTwo = buyXGetY("3FOR2", "2", "1", 10, true);

            // Two units: no complete group, nothing free.
            assertThat(discountOn("100.00", "2", threeForTwo)).isEqualByComparingTo("0.00");
            // Three: one group, one free.
            assertThat(discountOn("100.00", "3", threeForTwo)).isEqualByComparingTo("100.00");
            // Five: still one complete group. The fourth and fifth do not make a second.
            assertThat(discountOn("100.00", "5", threeForTwo)).isEqualByComparingTo("100.00");
            // Six: two groups, two free.
            assertThat(discountOn("100.00", "6", threeForTwo)).isEqualByComparingTo("200.00");
        }

        @Test
        @DisplayName("a promotion below its minimum quantity does not apply at all")
        void minimumQuantityIsEnforced() {
            PromotionCandidate bulkOnly =
                    new PromotionCandidate(
                            UUID.randomUUID(),
                            "BULK",
                            "Bulk discount",
                            PromotionType.PERCENTAGE_OFF,
                            new BigDecimal("0.20"),
                            null,
                            null,
                            new BigDecimal("5"),
                            10,
                            true);

            assertThat(
                            resolve(
                                            request("100.00", "4", true, VAT_16, "STANDARD")
                                                    .withPromotions(bulkOnly))
                                    .discounts())
                    .isEmpty();
            assertThat(
                            resolve(
                                            request("100.00", "5", true, VAT_16, "STANDARD")
                                                    .withPromotions(bulkOnly))
                                    .discountTotal()
                                    .amount())
                    .isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("stackable promotions apply in sequence, each on what is left")
        void stackablePromotionsCompound() {
            PriceBreakdown line =
                    resolve(
                            request("100.00", "1", true, ZERO_RATE, "ZERO_RATED")
                                    .withPromotions(
                                            percentage("A_HALF", "0.50", 10, true),
                                            percentage("B_HALF", "0.50", 20, true)));

            // Two halves off leave a quarter, not nothing. Applying both to the original would
            // give the customer the item free.
            assertThat(line.discounts()).hasSize(2);
            assertThat(line.discounts().get(0).amount().amount()).isEqualByComparingTo("50.00");
            assertThat(line.discounts().get(1).amount().amount()).isEqualByComparingTo("25.00");
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("25.00");
        }

        @Test
        @DisplayName("when anything is non-stackable, only the single best offer applies")
        void nonStackableTakesTheBestSingleOffer() {
            PriceBreakdown line =
                    resolve(
                            request("100.00", "1", true, ZERO_RATE, "ZERO_RATED")
                                    .withPromotions(
                                            percentage("SMALL", "0.10", 10, true),
                                            percentage("BIG", "0.30", 20, false)));

            // The customer gets the better of the two, not both: combining offers that were never
            // meant to combine is how a margin disappears.
            assertThat(line.discounts()).hasSize(1);
            assertThat(line.discounts().get(0).promotionCode()).isEqualTo("BIG");
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("70.00");
        }

        @Test
        void aDiscountNeverExceedsTheLine() {
            PriceBreakdown line =
                    resolve(
                            request("10.00", "1", true, ZERO_RATE, "ZERO_RATED")
                                    .withPromotions(amount("HUGE", "500.00", 10, true)));

            // A line can be free; it can never pay the customer.
            assertThat(line.discountTotal().amount()).isEqualByComparingTo("10.00");
            assertThat(line.lineTotal().isZero()).isTrue();
            assertThat(line.lineTotal().isNegative()).isFalse();
        }

        @Test
        @DisplayName("a bundle is left for the basket, because one line cannot see the others")
        void bundlesAreNotResolvedPerLine() {
            PriceBreakdown line =
                    resolve(
                            request("100.00", "1", true, VAT_16, "STANDARD")
                                    .withPromotions(
                                            new PromotionCandidate(
                                                    UUID.randomUUID(),
                                                    "MEALDEAL",
                                                    "Meal deal",
                                                    PromotionType.BUNDLE,
                                                    new BigDecimal("150.00"),
                                                    null,
                                                    null,
                                                    null,
                                                    10,
                                                    true)));

            assertThat(line.discounts()).isEmpty();
            assertThat(line.lineTotal().amount()).isEqualByComparingTo("100.00");
        }

        @Test
        @DisplayName("every applied discount names the offer, for the receipt and for disputes")
        void discountsAreAttributed() {
            PriceBreakdown line =
                    resolve(
                            request("100.00", "1", true, VAT_16, "STANDARD")
                                    .withPromotions(percentage("SPRING", "0.15", 10, true)));

            assertThat(line.discounts()).hasSize(1);
            assertThat(line.discounts().get(0).promotionCode()).isEqualTo("SPRING");
            assertThat(line.discounts().get(0).type()).isEqualTo("PERCENTAGE_OFF");
            assertThat(line.hasDiscounts()).isTrue();
        }
    }

    // --- determinism and invariants -------------------------------------------

    @Nested
    class DeterminismAndInvariants {

        @Test
        @DisplayName("the order promotions arrive in does not change the answer")
        void theOrderOfPromotionsDoesNotMatter() {
            PromotionCandidate first = percentage("AAA", "0.10", 20, true);
            PromotionCandidate second = percentage("BBB", "0.05", 10, true);

            PriceBreakdown oneWay =
                    resolve(
                            request("100.00", "1", true, VAT_16, "STANDARD")
                                    .withPromotions(first, second));
            PriceBreakdown otherWay =
                    resolve(
                            request("100.00", "1", true, VAT_16, "STANDARD")
                                    .withPromotions(second, first));

            // Two tills given the same basket must charge the same amount.
            assertThat(oneWay.lineTotal()).isEqualByComparingTo(otherWay.lineTotal());
            assertThat(oneWay.discounts().get(0).promotionCode())
                    .isEqualTo(otherWay.discounts().get(0).promotionCode())
                    // Priority 10 runs before priority 20, whatever order they were listed in.
                    .isEqualTo("BBB");
        }

        @Test
        void netPlusTaxAlwaysEqualsTheLineTotal() {
            List<PriceBreakdown> lines =
                    List.of(
                            resolve(request("116.00", "1", true, VAT_16, "STANDARD")),
                            resolve(request("100.00", "3", false, VAT_16, "STANDARD")),
                            resolve(request("250.00", "1.235", true, VAT_16, "STANDARD")),
                            resolve(request("60.00", "2", true, ZERO_RATE, "ZERO_RATED")),
                            resolve(
                                    request("99.99", "7", true, VAT_16, "STANDARD")
                                            .withPromotions(percentage("P", "0.13", 10, true))));

            for (PriceBreakdown line : lines) {
                assertThat(line.net().add(line.tax()))
                        .as("net + tax must equal the line total for %s", line.sku())
                        .isEqualByComparingTo(line.lineTotal());
                assertThat(line.subtotal().subtract(line.discountTotal()))
                        .as("discounted subtotal must equal subtotal - discounts")
                        .isEqualByComparingTo(line.discountedSubtotal());
            }
        }

        @Test
        void theBreakdownReportsWhereThePriceCameFrom() {
            PriceBreakdown fromList =
                    PriceResolver.resolve(
                            new PricingRequest(
                                    UUID.randomUUID(),
                                    "SKU-1",
                                    "Product",
                                    BigDecimal.ONE,
                                    Money.of("50.00", "KES"),
                                    PriceSource.PRICE_LIST,
                                    UUID.randomUUID(),
                                    true,
                                    "STANDARD",
                                    VAT_16,
                                    List.of()));

            // A price can always be accounted for: base, price list, or the label on the package.
            assertThat(fromList.priceSource()).isEqualTo(PriceSource.PRICE_LIST);
            assertThat(fromList.priceListId()).isNotNull();
        }
    }

    // --- builders -------------------------------------------------------------

    private static PriceBreakdown resolve(Request request) {
        return PriceResolver.resolve(request.toPricingRequest());
    }

    private static BigDecimal discountOn(
            String unitPrice, String quantity, PromotionCandidate promotion) {
        return resolve(
                        request(unitPrice, quantity, true, ZERO_RATE, "ZERO_RATED")
                                .withPromotions(promotion))
                .discountTotal()
                .amount();
    }

    private static Request request(
            String unitPrice,
            String quantity,
            boolean inclusive,
            BigDecimal rate,
            String taxClass) {
        return new Request(unitPrice, quantity, inclusive, rate, taxClass, List.of());
    }

    private record Request(
            String unitPrice,
            String quantity,
            boolean inclusive,
            BigDecimal rate,
            String taxClass,
            List<PromotionCandidate> promotions) {

        Request withPromotions(PromotionCandidate... candidates) {
            return new Request(unitPrice, quantity, inclusive, rate, taxClass, List.of(candidates));
        }

        PricingRequest toPricingRequest() {
            return new PricingRequest(
                    UUID.randomUUID(),
                    "SKU-TEST",
                    "Test product",
                    new BigDecimal(quantity),
                    Money.of(unitPrice, "KES"),
                    PriceSource.BASE_PRICE,
                    null,
                    inclusive,
                    taxClass,
                    rate,
                    promotions);
        }
    }

    private static PromotionCandidate percentage(
            String code, String fraction, int priority, boolean stackable) {
        return new PromotionCandidate(
                UUID.randomUUID(),
                code,
                code + " offer",
                PromotionType.PERCENTAGE_OFF,
                new BigDecimal(fraction),
                null,
                null,
                null,
                priority,
                stackable);
    }

    private static PromotionCandidate amount(
            String code, String value, int priority, boolean stackable) {
        return new PromotionCandidate(
                UUID.randomUUID(),
                code,
                code + " offer",
                PromotionType.AMOUNT_OFF,
                new BigDecimal(value),
                null,
                null,
                null,
                priority,
                stackable);
    }

    private static PromotionCandidate buyXGetY(
            String code, String buy, String get, int priority, boolean stackable) {
        return new PromotionCandidate(
                UUID.randomUUID(),
                code,
                code + " offer",
                PromotionType.BUY_X_GET_Y,
                null,
                new BigDecimal(buy),
                new BigDecimal(get),
                null,
                priority,
                stackable);
    }
}
