package com.pos.catalog.barcode;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.util.List;
import java.util.Optional;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.pos.catalog.domain.barcode.Ean13;
import com.pos.catalog.domain.barcode.ScaleBarcodeDecoder;
import com.pos.catalog.domain.barcode.ScaleBarcodeRule;
import com.pos.catalog.domain.barcode.ScaleBarcodeScan;

class ScaleBarcodeTest {

    /** 20 IIIII VVVVV C - prefix, item code, weight in grams, check digit. */
    private static final ScaleBarcodeRule WEIGHT_RULE =
            new ScaleBarcodeRule(
                    "20",
                    "Weight-embedded (grams)",
                    2,
                    5,
                    7,
                    5,
                    ScaleBarcodeRule.EmbeddedType.WEIGHT,
                    new BigDecimal("1000"));

    /** Same layout, but the digits are a price in cents. */
    private static final ScaleBarcodeRule PRICE_RULE =
            new ScaleBarcodeRule(
                    "21",
                    "Price-embedded (cents)",
                    2,
                    5,
                    7,
                    5,
                    ScaleBarcodeRule.EmbeddedType.PRICE,
                    new BigDecimal("100"));

    private static final List<ScaleBarcodeRule> RULES = List.of(WEIGHT_RULE, PRICE_RULE);

    @Nested
    class CheckDigits {

        @Test
        void computesTheCheckDigitForKnownBarcodes() {
            // Worked examples from the EAN-13 specification.
            assertThat(Ean13.checkDigitFor("400638133393")).isEqualTo(1);
            assertThat(Ean13.checkDigitFor("590123412345")).isEqualTo(7);
        }

        @Test
        void acceptsAValidBarcodeAndRejectsAMistypedOne() {
            String valid = Ean13.withCheckDigit("400638133393");
            assertThat(Ean13.hasValidCheckDigit(valid)).isTrue();

            // One digit changed: exactly the kind of misread the check digit exists to catch.
            String corrupted = "4006381333941";
            assertThat(Ean13.hasValidCheckDigit(corrupted)).isFalse();
        }

        @Test
        void rejectsAnythingThatIsNotThirteenDigits() {
            assertThat(Ean13.isWellFormed("12345")).isFalse();
            assertThat(Ean13.isWellFormed("40063813339A1")).isFalse();
            assertThat(Ean13.isWellFormed(null)).isFalse();
            assertThat(Ean13.hasValidCheckDigit("12345")).isFalse();
        }
    }

    @Nested
    class Decoding {

        @Test
        @DisplayName("a weight-embedded barcode yields the item and its weight in kilograms")
        void decodesAnEmbeddedWeight() {
            // Item 12345, 1.235 kg -> digits 20 12345 01235 + check
            String barcode = Ean13.withCheckDigit("201234501235");

            Optional<ScaleBarcodeScan> scan = ScaleBarcodeDecoder.decode(barcode, RULES);

            assertThat(scan).isPresent();
            assertThat(scan.get().itemCode()).isEqualTo("12345");
            assertThat(scan.get().weight()).isEqualByComparingTo("1.235");
            assertThat(scan.get().carriesWeight()).isTrue();
            assertThat(scan.get().carriesPrice()).isFalse();
            assertThat(scan.get().ruleName()).isEqualTo("Weight-embedded (grams)");
        }

        @Test
        @DisplayName("a price-embedded barcode yields the price the label already states")
        void decodesAnEmbeddedPrice() {
            // Item 54321, 308.75 -> digits 21 54321 30875 + check
            String barcode = Ean13.withCheckDigit("215432130875");

            Optional<ScaleBarcodeScan> scan = ScaleBarcodeDecoder.decode(barcode, RULES);

            assertThat(scan).isPresent();
            assertThat(scan.get().itemCode()).isEqualTo("54321");
            assertThat(scan.get().embeddedPrice()).isEqualByComparingTo("308.750");
            assertThat(scan.get().carriesPrice()).isTrue();
            assertThat(scan.get().weight()).isNull();
        }

        @Test
        @DisplayName("an ordinary product barcode is not treated as a scale barcode")
        void leavesOrdinaryBarcodesAlone() {
            // A normal retail EAN-13 with no scale prefix.
            assertThat(ScaleBarcodeDecoder.decode(Ean13.withCheckDigit("506000123456"), RULES))
                    .isEmpty();
        }

        @Test
        @DisplayName("a scale barcode that fails its check digit is refused, not guessed at")
        void refusesAMisreadScaleBarcode() {
            String valid = Ean13.withCheckDigit("201234501235");
            String corrupted =
                    valid.substring(0, 12)
                            + ((Character.getNumericValue(valid.charAt(12)) + 1) % 10);

            // Decoding it anyway would charge the customer for a weight that was never on the
            // scale.
            assertThat(ScaleBarcodeDecoder.decode(corrupted, RULES)).isEmpty();
        }

        @Test
        void refusesMalformedInput() {
            assertThat(ScaleBarcodeDecoder.decode("2012345", RULES)).isEmpty();
            assertThat(ScaleBarcodeDecoder.decode(null, RULES)).isEmpty();
            assertThat(ScaleBarcodeDecoder.decode(Ean13.withCheckDigit("201234501235"), List.of()))
                    .isEmpty();
        }

        @Test
        @DisplayName("an inactive rule is ignored, so a retired format stops being honoured")
        void ignoresInactiveRules() {
            ScaleBarcodeRule retired =
                    new ScaleBarcodeRule(
                            "20",
                            "Retired",
                            2,
                            5,
                            7,
                            5,
                            ScaleBarcodeRule.EmbeddedType.WEIGHT,
                            new BigDecimal("1000"));
            retired.setActive(false);

            assertThat(
                            ScaleBarcodeDecoder.decode(
                                    Ean13.withCheckDigit("201234501235"), List.of(retired)))
                    .isEmpty();
        }

        @Test
        @DisplayName("a rule whose offsets fall outside the barcode declines rather than guessing")
        void refusesAMisconfiguredRule() {
            ScaleBarcodeRule misconfigured =
                    new ScaleBarcodeRule(
                            "20",
                            "Broken",
                            2,
                            5,
                            11,
                            5,
                            ScaleBarcodeRule.EmbeddedType.WEIGHT,
                            new BigDecimal("1000"));

            assertThat(
                            ScaleBarcodeDecoder.decode(
                                    Ean13.withCheckDigit("201234501235"), List.of(misconfigured)))
                    .isEmpty();
        }

        @Test
        @DisplayName("a different layout decodes correctly, because the format is configuration")
        void supportsADifferentVendorLayout() {
            // Four-digit item code, six-digit weight in grams: 22 IIII WWWWWW C
            ScaleBarcodeRule otherVendor =
                    new ScaleBarcodeRule(
                            "22",
                            "Other vendor",
                            2,
                            4,
                            6,
                            6,
                            ScaleBarcodeRule.EmbeddedType.WEIGHT,
                            new BigDecimal("1000"));

            String barcode = Ean13.withCheckDigit("229876002500");
            Optional<ScaleBarcodeScan> scan =
                    ScaleBarcodeDecoder.decode(barcode, List.of(otherVendor));

            assertThat(scan).isPresent();
            assertThat(scan.get().itemCode()).isEqualTo("9876");
            assertThat(scan.get().weight()).isEqualByComparingTo("2.500");
        }
    }
}
