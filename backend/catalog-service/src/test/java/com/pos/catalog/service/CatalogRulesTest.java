package com.pos.catalog.service;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.catalog.domain.PromotionScope;
import com.pos.catalog.domain.PromotionType;
import com.pos.common.error.ApiException;

@DisplayName("Catalog rules")
class CatalogRulesTest {

    @Test
    @DisplayName("an image is recognised by its bytes: JPEG, PNG and WebP only")
    void imagesAreRecognisedByTheirBytes() {
        assertThat(ProductImageService.sniff(new byte[] {(byte) 0xFF, (byte) 0xD8, (byte) 0xFF, 0}))
                .isEqualTo("image/jpeg");
        assertThat(
                        ProductImageService.sniff(
                                new byte[] {(byte) 0x89, 'P', 'N', 'G', 0x0D, 0x0A, 0x1A, 0x0A}))
                .isEqualTo("image/png");
        assertThat(ProductImageService.sniff("RIFF\0\0\0\0WEBPVP8 ".getBytes()))
                .isEqualTo("image/webp");
        assertThat(ProductImageService.sniff("<svg onload=alert(1)>".getBytes())).isNull();
        assertThat(ProductImageService.sniff("GIF89a".getBytes())).isNull();
        assertThat(ProductImageService.sniff(new byte[] {(byte) 0xFF})).isNull();
    }

    private static PromotionService.Definition promotion(
            PromotionType type, String value, String buy, String get, String min) {
        return new PromotionService.Definition(
                "Offer",
                type,
                value == null ? null : new BigDecimal(value),
                buy == null ? null : new BigDecimal(buy),
                get == null ? null : new BigDecimal(get),
                min == null ? null : new BigDecimal(min),
                100,
                false,
                false,
                null,
                null,
                null,
                true,
                List.of(new PromotionService.Rule(PromotionScope.PRODUCT, UUID.randomUUID())));
    }

    private static String refusal(PromotionService.Definition definition) {
        try {
            PromotionService.validate(definition);
            return null;
        } catch (ApiException refused) {
            return refused.code();
        }
    }

    @Test
    @DisplayName("each kind of promotion needs what makes it apply, and a percentage is a fraction")
    void promotionsAreWellFormed() {
        assertThat(refusal(promotion(PromotionType.PERCENTAGE_OFF, "0.10", null, null, null)))
                .isNull();
        assertThat(refusal(promotion(PromotionType.PERCENTAGE_OFF, "10", null, null, null)))
                .isEqualTo("promotion.invalid_percentage");
        assertThat(refusal(promotion(PromotionType.AMOUNT_OFF, "0", null, null, null)))
                .isEqualTo("promotion.invalid_amount");
        assertThat(refusal(promotion(PromotionType.BUY_X_GET_Y, null, "2", "1", null))).isNull();
        assertThat(refusal(promotion(PromotionType.BUY_X_GET_Y, null, "2", null, null)))
                .isEqualTo("promotion.invalid_quantities");
        assertThat(refusal(promotion(PromotionType.BUNDLE, "300", null, null, "3"))).isNull();
        assertThat(refusal(promotion(PromotionType.BUNDLE, "300", null, null, null)))
                .isEqualTo("promotion.invalid_bundle");

        var noRules =
                new PromotionService.Definition(
                        "Offer",
                        PromotionType.AMOUNT_OFF,
                        BigDecimal.TEN,
                        null,
                        null,
                        null,
                        100,
                        false,
                        false,
                        null,
                        null,
                        null,
                        true,
                        List.of());
        assertThatThrownBy(() -> PromotionService.validate(noRules))
                .satisfies(
                        e ->
                                assertThat(((ApiException) e).code())
                                        .isEqualTo("promotion.rules_required"));
    }
}
