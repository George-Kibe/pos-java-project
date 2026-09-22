package com.pos.payment.client.daraja;

import java.time.Duration;
import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

/**
 * Safaricom Daraja settings.
 *
 * <p>Every credential here comes from the environment and none has a default. M-Pesa simply reports
 * itself unconfigured - and every M-Pesa tender fails fast as {@code PROVIDER_UNAVAILABLE} - until
 * they are all present, rather than the service refusing to start in a dev stack that does not need
 * M-Pesa.
 *
 * @param env {@code sandbox} or {@code production}; picks the Daraja host unless {@code baseUrl}
 *     overrides it
 * @param callbackBaseUrl the public address Daraja can reach (the tunnel, in development); the
 *     callback paths and the secret token are appended to it
 * @param callbackToken the secret path segment that authenticates a callback, since Daraja can
 *     neither sign a request nor present a token of its own
 * @param transactionType {@code CustomerPayBillOnline} for a paybill, {@code
 *     CustomerBuyGoodsOnline} for a till number
 * @param partyB the number the money goes to; the shortcode unless a till number differs from it
 * @param callbackAllowedIps if set, callbacks from anywhere else are refused
 * @param trustedProxyHops how many proxies (the gateway, a tunnel) sit between Daraja and this
 *     service, so the right X-Forwarded-For entry is read as the caller
 */
@ConfigurationProperties(prefix = "pos.payment.mpesa")
public record DarajaProperties(
        String env,
        String baseUrl,
        String consumerKey,
        String consumerSecret,
        String shortcode,
        String passkey,
        String callbackBaseUrl,
        String callbackToken,
        String initiatorName,
        String securityCredential,
        String transactionType,
        String partyB,
        List<String> callbackAllowedIps,
        Integer trustedProxyHops,
        Duration connectTimeout,
        Duration readTimeout,
        Duration firstQueryAfter,
        Duration queryInterval,
        Integer maxQueryAttempts,
        Duration giveUpAfter) {

    public static final String STK_CALLBACK_PATH = "/api/v1/payments/mpesa/callbacks/stk/";
    public static final String REVERSAL_RESULT_PATH = "/api/v1/payments/mpesa/callbacks/reversal/";
    public static final String REVERSAL_TIMEOUT_PATH =
            "/api/v1/payments/mpesa/callbacks/reversal-timeout/";

    /** Everything an STK Push needs. */
    public boolean isConfigured() {
        return present(consumerKey)
                && present(consumerSecret)
                && present(shortcode)
                && present(passkey)
                && present(callbackBaseUrl)
                && present(callbackToken);
    }

    /** A reversal also needs an API operator's name and encrypted credential. */
    public boolean canReverse() {
        return isConfigured() && present(initiatorName) && present(securityCredential);
    }

    public String host() {
        if (present(baseUrl)) {
            return baseUrl;
        }
        return "production".equalsIgnoreCase(env)
                ? "https://api.safaricom.co.ke"
                : "https://sandbox.safaricom.co.ke";
    }

    public String stkCallbackUrl() {
        return trimSlash(callbackBaseUrl) + STK_CALLBACK_PATH + callbackToken;
    }

    public String reversalResultUrl() {
        return trimSlash(callbackBaseUrl) + REVERSAL_RESULT_PATH + callbackToken;
    }

    public String reversalTimeoutUrl() {
        return trimSlash(callbackBaseUrl) + REVERSAL_TIMEOUT_PATH + callbackToken;
    }

    public String transactionTypeOrDefault() {
        return present(transactionType) ? transactionType : "CustomerPayBillOnline";
    }

    public String partyBOrDefault() {
        return present(partyB) ? partyB : shortcode;
    }

    public List<String> allowedIps() {
        return callbackAllowedIps == null ? List.of() : callbackAllowedIps;
    }

    public int trustedProxyHopsOrDefault() {
        return trustedProxyHops == null ? 1 : trustedProxyHops;
    }

    public Duration connectTimeoutOrDefault() {
        return connectTimeout == null ? Duration.ofSeconds(5) : connectTimeout;
    }

    public Duration readTimeoutOrDefault() {
        return readTimeout == null ? Duration.ofSeconds(20) : readTimeout;
    }

    public Duration firstQueryAfterOrDefault() {
        return firstQueryAfter == null ? Duration.ofSeconds(45) : firstQueryAfter;
    }

    public Duration queryIntervalOrDefault() {
        return queryInterval == null ? Duration.ofSeconds(30) : queryInterval;
    }

    public int maxQueryAttemptsOrDefault() {
        return maxQueryAttempts == null ? 20 : maxQueryAttempts;
    }

    public Duration giveUpAfterOrDefault() {
        return giveUpAfter == null ? Duration.ofMinutes(3) : giveUpAfter;
    }

    private static boolean present(String value) {
        return value != null && !value.isBlank();
    }

    private static String trimSlash(String url) {
        return url.endsWith("/") ? url.substring(0, url.length() - 1) : url;
    }
}
