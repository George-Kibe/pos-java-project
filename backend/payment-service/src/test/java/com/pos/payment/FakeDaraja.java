package com.pos.payment;

import java.io.IOException;
import java.io.OutputStream;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.List;
import java.util.Map;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.CopyOnWriteArrayList;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pos.events.EventJson;

/**
 * Safaricom Daraja, as far as this service can tell.
 *
 * <p>Answers OAuth, STK Push, STK Query and Reversal the way the sandbox does, including its
 * awkward parts: a query for a push still in progress is an HTTP 500 with error code {@code
 * 500.001.1001}, not a status. Records what it was sent, so a test can check the password, the
 * whole-shilling amount and the callback URL rather than trusting them.
 */
final class FakeDaraja {

    enum PushMode {
        ACCEPT,
        REJECT,
        DOWN
    }

    /** A query answer: a result code, or null for "still processing". */
    record QueryAnswer(Integer resultCode, String resultDesc) {}

    private final HttpServer server;
    private final AtomicInteger tokensIssued = new AtomicInteger();
    private final AtomicInteger pushes = new AtomicInteger();
    private final AtomicInteger reversals = new AtomicInteger();
    private final List<Map<String, Object>> pushRequests = new CopyOnWriteArrayList<>();
    private final List<Map<String, Object>> reversalRequests = new CopyOnWriteArrayList<>();
    private final List<String> authorizations = new CopyOnWriteArrayList<>();
    private final Map<String, QueryAnswer> queryAnswers = new ConcurrentHashMap<>();
    private final AtomicInteger queries = new AtomicInteger();
    private volatile PushMode pushMode = PushMode.ACCEPT;

    /** Tokens Daraja no longer honours, as after an early expiry. */
    private final java.util.Set<String> revoked = ConcurrentHashMap.newKeySet();

    /** An app without the M-Pesa Express product: every token is refused on these APIs. */
    private volatile boolean unsubscribed;

    FakeDaraja() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/oauth/v1/generate", this::token);
        server.createContext("/mpesa/stkpush/v1/processrequest", this::push);
        server.createContext("/mpesa/stkpushquery/v1/query", this::query);
        server.createContext("/mpesa/reversal/v1/request", this::reverse);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        pushMode = PushMode.ACCEPT;
        revoked.clear();
        unsubscribed = false;
        pushRequests.clear();
        reversalRequests.clear();
        authorizations.clear();
        queryAnswers.clear();
        pushes.set(0);
        reversals.set(0);
        queries.set(0);
        // tokensIssued is deliberately not reset: the client's cache outlives a test.
    }

    /** Every token issued so far stops working, the way an early expiry looks from outside. */
    void revokeIssuedTokens() {
        for (int n = 1; n <= tokensIssued.get(); n++) {
            revoked.add("Bearer token-" + n);
        }
    }

    void unsubscribe() {
        unsubscribed = true;
    }

    /** Daraja's answer to a token it will not honour: HTTP 404, not 401. */
    private boolean refusedToken(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (unsubscribed || auth == null || revoked.contains(auth)) {
            exchange.getRequestBody().readAllBytes();
            respond(
                    exchange,
                    404,
                    "{\"requestId\":\"x\",\"errorCode\":\"404.001.03\","
                            + "\"errorMessage\":\"Invalid Access Token\"}");
            return true;
        }
        return false;
    }

    void pushMode(PushMode mode) {
        pushMode = mode;
    }

    void answerQuery(String checkoutRequestId, QueryAnswer answer) {
        queryAnswers.put(checkoutRequestId, answer);
    }

    int tokensIssued() {
        return tokensIssued.get();
    }

    int queries() {
        return queries.get();
    }

    List<Map<String, Object>> pushRequests() {
        return pushRequests;
    }

    List<Map<String, Object>> reversalRequests() {
        return reversalRequests;
    }

    List<String> authorizations() {
        return authorizations;
    }

    String lastCheckoutRequestId() {
        return "ws_CO_" + pushes.get();
    }

    private void token(HttpExchange exchange) throws IOException {
        String auth = exchange.getRequestHeaders().getFirst("Authorization");
        if (auth == null || !auth.startsWith("Basic ")) {
            respond(exchange, 400, "{\"errorMessage\":\"Invalid Authentication passed\"}");
            return;
        }
        int n = tokensIssued.incrementAndGet();
        respond(exchange, 200, "{\"access_token\":\"token-" + n + "\",\"expires_in\":\"3599\"}");
    }

    @SuppressWarnings("unchecked")
    private void push(HttpExchange exchange) throws IOException {
        if (refusedToken(exchange)) {
            return;
        }
        authorizations.add(exchange.getRequestHeaders().getFirst("Authorization"));
        Map<String, Object> body =
                EventJson.mapper().readValue(exchange.getRequestBody(), Map.class);
        switch (pushMode) {
            case DOWN -> respond(exchange, 503, "{\"errorMessage\":\"Service Unavailable\"}");
            case REJECT ->
                    respond(
                            exchange,
                            400,
                            "{\"errorCode\":\"400.002.02\",\"errorMessage\":\"Bad Request - Invalid"
                                    + " PhoneNumber\"}");
            case ACCEPT -> {
                pushRequests.add(body);
                int n = pushes.incrementAndGet();
                respond(
                        exchange,
                        200,
                        """
                        {"MerchantRequestID":"mr-%d","CheckoutRequestID":"ws_CO_%d",\
                        "ResponseCode":"0","ResponseDescription":"Success. Request accepted",\
                        "CustomerMessage":"Success. Request accepted"}"""
                                .formatted(n, n));
            }
        }
    }

    @SuppressWarnings("unchecked")
    private void query(HttpExchange exchange) throws IOException {
        if (refusedToken(exchange)) {
            return;
        }
        queries.incrementAndGet();
        Map<String, Object> body =
                EventJson.mapper().readValue(exchange.getRequestBody(), Map.class);
        QueryAnswer answer = queryAnswers.get(String.valueOf(body.get("CheckoutRequestID")));
        if (answer == null || answer.resultCode() == null) {
            respond(
                    exchange,
                    500,
                    "{\"requestId\":\"x\",\"errorCode\":\"500.001.1001\",\"errorMessage\":\"The"
                            + " transaction is being processed\"}");
            return;
        }
        respond(
                exchange,
                200,
                """
                {"ResponseCode":"0","ResponseDescription":"The service request has been accepted\
                 successsfully","MerchantRequestID":"mr","CheckoutRequestID":"%s",\
                "ResultCode":"%d","ResultDesc":"%s"}"""
                        .formatted(
                                body.get("CheckoutRequestID"),
                                answer.resultCode(),
                                answer.resultDesc()));
    }

    @SuppressWarnings("unchecked")
    private void reverse(HttpExchange exchange) throws IOException {
        if (refusedToken(exchange)) {
            return;
        }
        Map<String, Object> body =
                EventJson.mapper().readValue(exchange.getRequestBody(), Map.class);
        reversalRequests.add(body);
        int n = reversals.incrementAndGet();
        respond(
                exchange,
                200,
                """
                {"OriginatorConversationID":"oc-%d","ConversationID":"AG_%d",\
                "ResponseCode":"0","ResponseDescription":"Accept the service request successfully."}"""
                        .formatted(n, n));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
