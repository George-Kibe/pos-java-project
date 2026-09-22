package com.pos.payment.domain.policy;

import java.util.Optional;
import java.util.regex.Pattern;

/**
 * Kenyan mobile numbers, as a cashier types them and as Daraja wants them.
 *
 * <p>Daraja expects {@code 2547XXXXXXXX} or {@code 2541XXXXXXXX}. Cashiers type {@code 0712...},
 * {@code +254712...}, {@code 712...}, with spaces. Anything that is not one of those shapes is
 * refused here rather than sent, because a push to a mistyped number prompts a stranger.
 */
public final class PhoneNumbers {

    private static final Pattern KENYAN_MOBILE = Pattern.compile("^254[17]\\d{8}$");

    private PhoneNumbers() {}

    /** The MSISDN in Daraja's form, or empty when it is not a Kenyan mobile number. */
    public static Optional<String> toMsisdn(String typed) {
        if (typed == null) {
            return Optional.empty();
        }
        String digits = typed.replaceAll("[\\s()-]", "");
        if (digits.startsWith("+")) {
            digits = digits.substring(1);
        }
        if (digits.startsWith("0") && digits.length() == 10) {
            digits = "254" + digits.substring(1);
        } else if (digits.length() == 9 && (digits.startsWith("7") || digits.startsWith("1"))) {
            digits = "254" + digits;
        }
        return KENYAN_MOBILE.matcher(digits).matches() ? Optional.of(digits) : Optional.empty();
    }

    /** Last three digits only - enough for support to match a customer, not enough to call them. */
    public static String mask(String number) {
        if (number == null || number.isBlank()) {
            return null;
        }
        String digits = number.replaceAll("\\D", "");
        if (digits.length() <= 3) {
            return "***";
        }
        return "*".repeat(digits.length() - 3) + digits.substring(digits.length() - 3);
    }
}
