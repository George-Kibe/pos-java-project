package com.pos.catalog.domain.barcode;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.util.List;
import java.util.Optional;

/**
 * Reads a barcode printed by a deli or butchery scale.
 *
 * <p>Driven entirely by configured rules. There is no single standard for these: the prefix and the
 * digit positions vary by retailer and by scale vendor, so assuming one layout is how a shop ends
 * up charging for 5 kg of mince because the weight field was two digits further along.
 *
 * <p>Pure, so every format can be tested without a database.
 */
public final class ScaleBarcodeDecoder {

    private ScaleBarcodeDecoder() {}

    /** Weight in kilograms and price both carry three decimals internally. */
    private static final int VALUE_SCALE = 3;

    /**
     * Decodes {@code barcode} against the first matching rule.
     *
     * @return empty when this is an ordinary product barcode rather than a scale one, or when it
     *     fails its check digit
     */
    public static Optional<ScaleBarcodeScan> decode(String barcode, List<ScaleBarcodeRule> rules) {
        if (!Ean13.isWellFormed(barcode)) {
            return Optional.empty();
        }

        Optional<ScaleBarcodeRule> matched =
                rules.stream()
                        .filter(ScaleBarcodeRule::isActive)
                        .filter(rule -> barcode.startsWith(rule.getPrefix()))
                        .findFirst();

        if (matched.isEmpty()) {
            return Optional.empty();
        }

        // Checked only once a rule matched: an ordinary barcode with a bad check digit is not this
        // decoder's problem, but a scale barcode with one must not be trusted with a weight.
        if (!Ean13.hasValidCheckDigit(barcode)) {
            return Optional.empty();
        }

        ScaleBarcodeRule rule = matched.get();
        String itemCode = slice(barcode, rule.getItemCodeStart(), rule.getItemCodeLength());
        String rawValue = slice(barcode, rule.getValueStart(), rule.getValueLength());
        if (itemCode == null || rawValue == null) {
            return Optional.empty();
        }

        BigDecimal value =
                new BigDecimal(rawValue)
                        .divide(rule.getValueDivisor(), VALUE_SCALE, RoundingMode.HALF_UP);

        return Optional.of(
                switch (rule.getEmbeddedType()) {
                    case WEIGHT ->
                            new ScaleBarcodeScan(barcode, rule.getName(), itemCode, value, null);
                    case PRICE ->
                            new ScaleBarcodeScan(barcode, rule.getName(), itemCode, null, value);
                });
    }

    private static String slice(String barcode, int start, int length) {
        if (start < 0 || length <= 0 || start + length > barcode.length()) {
            // A rule whose offsets fall outside the barcode is misconfigured; decoding something
            // arbitrary would be worse than declining.
            return null;
        }
        return barcode.substring(start, start + length);
    }
}
