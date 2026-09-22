package com.pos.payment.client.daraja;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Clock;
import java.time.Instant;
import java.time.ZoneId;
import java.time.format.DateTimeFormatter;
import java.util.Base64;
import java.util.LinkedHashMap;
import java.util.Map;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.HttpServerErrorException;
import org.springframework.web.client.ResourceAccessException;
import org.springframework.web.client.RestClient;
import org.springframework.web.client.RestClientException;

/**
 * Safaricom Daraja: OAuth, STK Push, STK Query and Reversal.
 *
 * <p>The access token is cached until shortly before it expires. Daraja rate-limits the token
 * endpoint, and fetching one per push would both slow every sale and eventually get the shortcode
 * throttled at the busiest hour.
 */
public class DarajaClient {

    private static final Logger log = LoggerFactory.getLogger(DarajaClient.class);

    private static final ZoneId NAIROBI = ZoneId.of("Africa/Nairobi");
    private static final DateTimeFormatter TIMESTAMP =
            DateTimeFormatter.ofPattern("yyyyMMddHHmmss").withZone(NAIROBI);
    private static final String STILL_PROCESSING = "500.001.1001";
    private static final String INVALID_TOKEN = "404.001.03";
    private static final ParameterizedTypeReference<Map<String, Object>> JSON =
            new ParameterizedTypeReference<>() {};

    private final RestClient http;
    private final DarajaProperties properties;
    private final Clock clock;

    private String token;
    private Instant tokenExpiresAt = Instant.EPOCH;

    public DarajaClient(RestClient http, DarajaProperties properties, Clock clock) {
        this.http = http;
        this.properties = properties;
        this.clock = clock;
    }

    /** What Daraja answered to a push. */
    public record StkPushAccepted(String merchantRequestId, String checkoutRequestId) {}

    /** What a status query learned. */
    public sealed interface QueryResult {
        record Completed(int resultCode, String resultDesc) implements QueryResult {}

        record StillProcessing() implements QueryResult {}
    }

    public record ReversalAccepted(String originatorConversationId, String conversationId) {}

    public StkPushAccepted stkPush(
            String msisdn, BigDecimal amount, String accountReference, String description) {
        String timestamp = TIMESTAMP.format(clock.instant());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", properties.shortcode());
        body.put("Password", password(timestamp));
        body.put("Timestamp", timestamp);
        body.put("TransactionType", properties.transactionTypeOrDefault());
        body.put("Amount", amount.intValueExact());
        body.put("PartyA", msisdn);
        body.put("PartyB", properties.partyBOrDefault());
        body.put("PhoneNumber", msisdn);
        body.put("CallBackURL", properties.stkCallbackUrl());
        // Daraja truncates silently past these lengths; better to do it knowingly.
        body.put("AccountReference", truncate(accountReference, 12));
        body.put("TransactionDesc", truncate(description, 13));

        Map<String, Object> answer = post("/mpesa/stkpush/v1/processrequest", body);
        if (!"0".equals(string(answer, "ResponseCode"))) {
            throw new DarajaException(
                    DarajaException.Kind.REJECTED,
                    "STK Push refused: " + string(answer, "ResponseDescription"));
        }
        return new StkPushAccepted(
                string(answer, "MerchantRequestID"), string(answer, "CheckoutRequestID"));
    }

    public QueryResult stkQuery(String checkoutRequestId) {
        String timestamp = TIMESTAMP.format(clock.instant());
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("BusinessShortCode", properties.shortcode());
        body.put("Password", password(timestamp));
        body.put("Timestamp", timestamp);
        body.put("CheckoutRequestID", checkoutRequestId);
        try {
            Map<String, Object> answer = post("/mpesa/stkpushquery/v1/query", body);
            String code = string(answer, "ResultCode");
            if (code == null) {
                return new QueryResult.StillProcessing();
            }
            return new QueryResult.Completed(
                    Integer.parseInt(code.trim()), string(answer, "ResultDesc"));
        } catch (DarajaException e) {
            // "The transaction is being processed" arrives as an HTTP 500, not as a status.
            if (e.getMessage() != null && e.getMessage().contains(STILL_PROCESSING)) {
                return new QueryResult.StillProcessing();
            }
            throw e;
        }
    }

    /**
     * Reverses a whole M-Pesa transaction. The answer arrives later, at the result URL.
     *
     * @param amount the whole-shilling amount the customer was charged
     */
    public ReversalAccepted reverse(String receiptNumber, BigDecimal amount, String remarks) {
        Map<String, Object> body = new LinkedHashMap<>();
        body.put("Initiator", properties.initiatorName());
        body.put("SecurityCredential", properties.securityCredential());
        body.put("CommandID", "TransactionReversal");
        body.put("TransactionID", receiptNumber);
        body.put("Amount", amount.intValueExact());
        body.put("ReceiverParty", properties.shortcode());
        // Daraja's own spelling.
        body.put("RecieverIdentifierType", "11");
        body.put("ResultURL", properties.reversalResultUrl());
        body.put("QueueTimeOutURL", properties.reversalTimeoutUrl());
        body.put("Remarks", truncate(remarks, 100));
        body.put("Occasion", "Refund");

        Map<String, Object> answer = post("/mpesa/reversal/v1/request", body);
        if (!"0".equals(string(answer, "ResponseCode"))) {
            throw new DarajaException(
                    DarajaException.Kind.REJECTED,
                    "Reversal refused: " + string(answer, "ResponseDescription"));
        }
        return new ReversalAccepted(
                string(answer, "OriginatorConversationID"), string(answer, "ConversationID"));
    }

    // --- plumbing ---------------------------------------------------------------

    /**
     * One call, with one retry when Daraja says the token is no good.
     *
     * <p>Daraja answers an expired or revoked token with HTTP 404 and error code {@code 404.001.03}
     * - not a 401 - so a cache that only listened for 401 would keep presenting a dead token, and
     * every M-Pesa sale would fail until the cached expiry passed. The retry is safe even for an
     * STK Push: a request refused at the token check was never processed.
     */
    private Map<String, Object> post(String path, Map<String, Object> body) {
        try {
            return send(path, body);
        } catch (TokenRejected rejected) {
            log.warn("Daraja rejected the cached access token on {}; fetching a new one", path);
            forgetToken();
            try {
                return send(path, body);
            } catch (TokenRejected again) {
                // A fresh token refused too: the app is not allowed this API, or the key is wrong.
                throw new DarajaException(
                        DarajaException.Kind.REJECTED,
                        "Daraja refused a fresh access token for "
                                + path
                                + ": the app may not be subscribed to this API ("
                                + again.getMessage()
                                + ")");
            }
        }
    }

    private Map<String, Object> send(String path, Map<String, Object> body) {
        try {
            Map<String, Object> answer =
                    http.post()
                            .uri(properties.host() + path)
                            .header(HttpHeaders.AUTHORIZATION, "Bearer " + accessToken())
                            .body(body)
                            .retrieve()
                            .body(JSON);
            return answer == null ? Map.of() : answer;
        } catch (HttpClientErrorException e) {
            String detail = describe(e.getResponseBodyAsString());
            if (e.getStatusCode().value() == 401 || detail.contains(INVALID_TOKEN)) {
                throw new TokenRejected(detail);
            }
            throw new DarajaException(DarajaException.Kind.REJECTED, detail, e);
        } catch (HttpServerErrorException e) {
            throw new DarajaException(
                    DarajaException.Kind.UNAVAILABLE, describe(e.getResponseBodyAsString()), e);
        } catch (ResourceAccessException e) {
            throw new DarajaException(
                    DarajaException.Kind.UNAVAILABLE, "Daraja unreachable: " + e.getMessage(), e);
        } catch (RestClientException e) {
            throw new DarajaException(
                    DarajaException.Kind.UNAVAILABLE,
                    "Daraja answered oddly: " + e.getMessage(),
                    e);
        }
    }

    /** Daraja refused the token itself, before looking at the request. */
    private static final class TokenRejected extends RuntimeException {
        TokenRejected(String message) {
            super(message);
        }
    }

    synchronized String accessToken() {
        Instant now = clock.instant();
        if (token != null && now.isBefore(tokenExpiresAt)) {
            return token;
        }
        String basic =
                Base64.getEncoder()
                        .encodeToString(
                                (properties.consumerKey() + ":" + properties.consumerSecret())
                                        .getBytes(StandardCharsets.UTF_8));
        try {
            Map<String, Object> answer =
                    http.get()
                            .uri(
                                    properties.host()
                                            + "/oauth/v1/generate?grant_type=client_credentials")
                            .header(HttpHeaders.AUTHORIZATION, "Basic " + basic)
                            .retrieve()
                            .body(JSON);
            if (answer == null || string(answer, "access_token") == null) {
                throw new DarajaException(
                        DarajaException.Kind.UNAVAILABLE, "Daraja issued no access token");
            }
            token = string(answer, "access_token");
            long seconds =
                    Long.parseLong(String.valueOf(answer.getOrDefault("expires_in", "3599")));
            // A minute's margin, so a token never expires between being read and being used.
            tokenExpiresAt = now.plusSeconds(Math.max(seconds - 60, 0));
            log.debug("Fetched a Daraja access token valid for {}s", seconds);
            return token;
        } catch (HttpClientErrorException e) {
            throw new DarajaException(
                    DarajaException.Kind.REJECTED,
                    "Daraja refused the consumer key and secret (HTTP "
                            + e.getStatusCode().value()
                            + ")",
                    e);
        } catch (RestClientException e) {
            throw new DarajaException(
                    DarajaException.Kind.UNAVAILABLE, "Could not fetch a Daraja token", e);
        }
    }

    private synchronized void forgetToken() {
        token = null;
        tokenExpiresAt = Instant.EPOCH;
    }

    private String password(String timestamp) {
        return Base64.getEncoder()
                .encodeToString(
                        (properties.shortcode() + properties.passkey() + timestamp)
                                .getBytes(StandardCharsets.UTF_8));
    }

    private static String string(Map<String, Object> map, String key) {
        Object value = map.get(key);
        return value == null ? null : String.valueOf(value);
    }

    private static String truncate(String value, int max) {
        if (value == null) {
            return "";
        }
        return value.length() <= max ? value : value.substring(0, max);
    }

    /** Daraja's error body is {@code {"errorCode": ..., "errorMessage": ...}}; keep both. */
    private static String describe(String body) {
        return body == null || body.isBlank()
                ? "no body"
                : body.length() > 400 ? body.substring(0, 400) : body;
    }
}
