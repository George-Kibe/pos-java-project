package com.pos.purchasing;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.LinkedHashMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pos.events.EventJson;

import tools.jackson.databind.JsonNode;

/**
 * Catalog's cost check, faked with catalog's arithmetic: a cost with VAT loses it at four places,
 * HALF_UP. Products are zero-rated unless a test gives them a rate, so tests that never think about
 * VAT keep their figures.
 */
final class FakeCatalog {

    private final HttpServer server;
    private final Map<UUID, BigDecimal> rates = new ConcurrentHashMap<>();
    private volatile boolean down;
    private volatile String lastAuthorization;

    FakeCatalog() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/v1/pricing/cost-check", this::costCheck);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void rate(UUID productId, String rate) {
        rates.put(productId, new BigDecimal(rate));
    }

    void reset() {
        rates.clear();
        down = false;
        lastAuthorization = null;
    }

    void goDown() {
        down = true;
    }

    String lastAuthorization() {
        return lastAuthorization;
    }

    private void costCheck(HttpExchange exchange) throws IOException {
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (down) {
            respond(exchange, 503, "{\"detail\":\"down\"}");
            return;
        }
        JsonNode request = EventJson.mapper().readTree(exchange.getRequestBody());
        boolean includesTax = request.path("costIncludesTax").asBoolean(false);
        List<Map<String, Object>> answers = new ArrayList<>();
        for (JsonNode line : request.get("lines")) {
            UUID productId = UUID.fromString(line.get("productId").asString());
            BigDecimal cost = new BigDecimal(line.get("unitCost").asString());
            BigDecimal rate = rates.getOrDefault(productId, BigDecimal.ZERO);
            BigDecimal net =
                    includesTax
                            ? cost.divide(BigDecimal.ONE.add(rate), 4, RoundingMode.HALF_UP)
                            : cost.setScale(4, RoundingMode.HALF_UP);
            Map<String, Object> answer = new LinkedHashMap<>();
            answer.put("productId", productId);
            answer.put("unitCost", cost);
            answer.put("taxRate", rate);
            answer.put("netUnitCost", net);
            answer.put("status", "NO_TARGET");
            answers.add(answer);
        }
        respond(exchange, 200, EventJson.write(answers));
    }

    private static void respond(HttpExchange exchange, int status, String body) throws IOException {
        byte[] bytes = body.getBytes(StandardCharsets.UTF_8);
        exchange.getResponseHeaders().add("Content-Type", "application/json");
        exchange.sendResponseHeaders(status, bytes.length);
        try (OutputStream out = exchange.getResponseBody()) {
            out.write(bytes);
        }
    }
}
