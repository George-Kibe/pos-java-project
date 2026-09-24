package com.pos.common.logging;

import java.util.List;
import java.util.regex.Matcher;
import java.util.regex.Pattern;

/**
 * Redacts credentials and personal data from text on its way to the log.
 *
 * <p>This is a safety net, not a licence to log secrets. The first line of defence is not putting a
 * token or an OTP into a log statement at all. But request bodies, exception messages and
 * third-party library output all leak, and a log aggregator is a far softer target than the
 * database - so anything that does slip through is caught here.
 *
 * <p>When a new sensitive field is introduced anywhere in the system, add it to {@link
 * #SENSITIVE_KEYS} in the same change.
 */
public final class LogMasker {

    private LogMasker() {}

    public static final String REDACTED = "***";

    /** Field names whose values are replaced wholesale, in JSON or key=value form. */
    private static final List<String> SENSITIVE_KEYS =
            List.of(
                    "password",
                    "currentPassword",
                    "newPassword",
                    "passwordHash",
                    "secret",
                    "clientSecret",
                    "consumerSecret",
                    "passkey",
                    "securityCredential",
                    "apiKey",
                    "token",
                    "accessToken",
                    "refreshToken",
                    "resetToken",
                    "idToken",
                    "approvalToken",
                    "otp",
                    "otpCode",
                    "pin",
                    "authorization",
                    "keystorePassword");

    /** "key": "value" and "key":"value" in JSON. */
    private static final Pattern JSON_FIELD =
            Pattern.compile(
                    "(?i)(\"(?:" + String.join("|", SENSITIVE_KEYS) + ")\"\\s*:\\s*)\"[^\"]*\"");

    /** key=value in query strings, form bodies and toString() output. */
    private static final Pattern KEY_VALUE =
            Pattern.compile(
                    "(?i)(?<![A-Za-z0-9])((?:"
                            + String.join("|", SENSITIVE_KEYS)
                            + ")\\s*[=:]\\s*)([^\\s,;&)}\\]]+)");

    /** Authorization headers, whatever the scheme. */
    private static final Pattern BEARER =
            Pattern.compile("(?i)\\b(Bearer|Basic)\\s+[A-Za-z0-9._~+/=-]+");

    /** A bare JWT appearing anywhere. */
    private static final Pattern JWT =
            Pattern.compile("\\beyJ[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\.[A-Za-z0-9_-]{5,}\\b");

    /** 13-19 digit card numbers, optionally separated - keep the last 4 for reconciliation. */
    private static final Pattern PAN = Pattern.compile("\\b(?:\\d[ -]?){12,18}\\d\\b");

    /** MSISDN in local or international form - keep the last 3 so support can match a customer. */
    private static final Pattern PHONE = Pattern.compile("\\b(?:\\+?254|0)(7|1)\\d{7}(\\d)\\b");

    /**
     * The secret segment of an M-Pesa callback URL. Daraja cannot authenticate to us, so the path
     * token is the credential, and a logged request path would otherwise hand it out.
     */
    private static final Pattern CALLBACK_TOKEN =
            Pattern.compile("(/mpesa/callbacks/[a-z-]+/)[^/\\s?\"]+");

    public static String mask(String input) {
        if (input == null || input.isEmpty()) {
            return input;
        }
        String out = input;
        // Scheme-prefixed credentials and bare JWTs go first. If KEY_VALUE ran before them it
        // would replace only the scheme word of "Authorization: Bearer <token>", leaving the
        // token in the clear.
        out = BEARER.matcher(out).replaceAll("$1 " + REDACTED);
        out = CALLBACK_TOKEN.matcher(out).replaceAll("$1" + REDACTED);
        out = JWT.matcher(out).replaceAll(REDACTED);
        out = JSON_FIELD.matcher(out).replaceAll("$1\"" + REDACTED + "\"");
        out = KEY_VALUE.matcher(out).replaceAll("$1" + REDACTED);
        out = maskPan(out);
        out = PHONE.matcher(out).replaceAll("$1******$2");
        return out;
    }

    private static String maskPan(String input) {
        Matcher m = PAN.matcher(input);
        StringBuilder sb = new StringBuilder();
        while (m.find()) {
            String digits = m.group().replaceAll("[ -]", "");
            String replacement =
                    "*".repeat(digits.length() - 4) + digits.substring(digits.length() - 4);
            m.appendReplacement(sb, Matcher.quoteReplacement(replacement));
        }
        m.appendTail(sb);
        return sb.toString();
    }
}
