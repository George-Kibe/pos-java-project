package com.pos.catalog.domain.barcode;

/**
 * EAN-13 check digit arithmetic.
 *
 * <p>Worth verifying rather than trusting the scanner. A scale prints the barcode and the scanner
 * reads it, and a misread that happens to be the right length would otherwise decode to a plausible
 * but wrong item code or weight - charging the customer for something they did not buy, with
 * nothing to indicate anything went wrong.
 */
public final class Ean13 {

    private Ean13() {}

    public static final int LENGTH = 13;

    public static boolean isWellFormed(String barcode) {
        if (barcode == null || barcode.length() != LENGTH) {
            return false;
        }
        for (int i = 0; i < LENGTH; i++) {
            if (!Character.isDigit(barcode.charAt(i))) {
                return false;
            }
        }
        return true;
    }

    /** Whether the final digit matches the other twelve. */
    public static boolean hasValidCheckDigit(String barcode) {
        if (!isWellFormed(barcode)) {
            return false;
        }
        int expected = checkDigitFor(barcode.substring(0, LENGTH - 1));
        return expected == Character.getNumericValue(barcode.charAt(LENGTH - 1));
    }

    /**
     * The check digit for the first twelve digits.
     *
     * <p>Digits are weighted alternately 1 and 3 from the left, and the check digit is whatever
     * brings the total up to the next multiple of ten.
     */
    public static int checkDigitFor(String twelveDigits) {
        if (twelveDigits == null || twelveDigits.length() != LENGTH - 1) {
            throw new IllegalArgumentException("Expected 12 digits, got: " + twelveDigits);
        }
        int sum = 0;
        for (int i = 0; i < LENGTH - 1; i++) {
            int digit = Character.getNumericValue(twelveDigits.charAt(i));
            if (digit < 0) {
                throw new IllegalArgumentException("Not a digit at position " + i);
            }
            sum += (i % 2 == 0) ? digit : digit * 3;
        }
        return (10 - (sum % 10)) % 10;
    }

    /** The twelve digits plus their check digit. */
    public static String withCheckDigit(String twelveDigits) {
        return twelveDigits + checkDigitFor(twelveDigits);
    }
}
