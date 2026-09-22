package com.pos.sales;

import java.io.IOException;
import java.io.OutputStream;
import java.math.BigDecimal;
import java.math.RoundingMode;
import java.net.InetSocketAddress;
import java.nio.charset.StandardCharsets;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.ConcurrentHashMap;
import java.util.concurrent.atomic.AtomicInteger;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

import com.pos.events.EventJson;

import tools.jackson.databind.JsonNode;

/**
 * Catalog's pricing endpoint, faked with real arithmetic.
 *
 * <p>Answers {@code POST /api/v1/pricing/resolve} in catalog's exact wire shape, computing each
 * line the way catalog does: subtotal, less any promotion, then tax extracted from an inclusive
 * price or added to an exclusive one, rounded once at four places. Sales must carry those figures
 * through verbatim, and these tests prove it does; the running-stack check proves the shape matches
 * the real catalog.
 *
 * <p>Not WireMock: a static stub cannot price an arbitrary basket, and a response transformer would
 * be more code than this.
 */
final class FakeCatalog {

    /** A product as the fake prices it. */
    record Product(
            UUID id,
            String sku,
            String name,
            BigDecimal unitPrice,
            String taxClass,
            BigDecimal taxRate,
            boolean inclusive,
            BigDecimal discountPerUnit) {}

    private final HttpServer server;
    private final Map<UUID, Product> products = new ConcurrentHashMap<>();
    private final AtomicInteger calls = new AtomicInteger();
    private volatile boolean down;
    private volatile String lastCorrelationId;
    private volatile String lastAuthorization;

    FakeCatalog() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/v1/pricing/resolve", this::resolve);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void register(Product product) {
        products.put(product.id(), product);
    }

    void reset() {
        products.clear();
        calls.set(0);
        down = false;
        lastCorrelationId = null;
        lastAuthorization = null;
    }

    /** Makes every call fail, as a crashed catalog would. */
    void goDown() {
        down = true;
    }

    int calls() {
        return calls.get();
    }

    String lastCorrelationId() {
        return lastCorrelationId;
    }

    String lastAuthorization() {
        return lastAuthorization;
    }

    private void resolve(HttpExchange exchange) throws IOException {
        calls.incrementAndGet();
        lastCorrelationId = exchange.getRequestHeaders().getFirst("X-Correlation-Id");
        lastAuthorization = exchange.getRequestHeaders().getFirst("Authorization");
        if (down) {
            respond(exchange, 503, "{\"detail\":\"down\"}");
            return;
        }

        JsonNode request = EventJson.mapper().readTree(exchange.getRequestBody());
        List<Map<String, Object>> answers = new ArrayList<>();

        for (JsonNode line : request.get("lines")) {
            UUID productId = UUID.fromString(line.get("productId").asString());
            BigDecimal quantity = new BigDecimal(line.get("quantity").asString());
            Product product = products.get(productId);
            if (product == null) {
                respond(exchange, 404, "{\"detail\":\"unknown product\"}");
                return;
            }
            answers.add(price(product, quantity));
        }
        respond(exchange, 200, EventJson.write(answers));
    }

    /** Catalog's order of operations: subtotal, then discounts, then tax. */
    static Map<String, Object> price(Product product, BigDecimal quantity) {
        BigDecimal subtotal =
                product.unitPrice().multiply(quantity).setScale(4, RoundingMode.HALF_UP);
        BigDecimal discount =
                product.discountPerUnit() == null
                        ? BigDecimal.ZERO.setScale(4)
                        : product.discountPerUnit()
                                .multiply(quantity)
                                .setScale(4, RoundingMode.HALF_UP);
        BigDecimal discounted = subtotal.subtract(discount);

        BigDecimal net;
        BigDecimal tax;
        BigDecimal gross;
        if (product.inclusive()) {
            gross = discounted;
            net = gross.divide(BigDecimal.ONE.add(product.taxRate()), 4, RoundingMode.HALF_UP);
            tax = gross.subtract(net);
        } else {
            net = discounted;
            tax = net.multiply(product.taxRate()).setScale(4, RoundingMode.HALF_UP);
            gross = net.add(tax);
        }

        List<Map<String, Object>> discounts =
                discount.signum() == 0
                        ? List.of()
                        : List.of(
                                Map.of(
                                        "promotionId",
                                        UUID.randomUUID().toString(),
                                        "code",
                                        "PROMO-" + product.sku(),
                                        "name",
                                        "Promotion on " + product.name(),
                                        "type",
                                        "AMOUNT",
                                        "amount",
                                        discount));

        java.util.HashMap<String, Object> answer = new java.util.HashMap<>();
        answer.put("productId", product.id().toString());
        answer.put("sku", product.sku());
        answer.put("productName", product.name());
        answer.put("quantity", quantity);
        answer.put("unitPrice", product.unitPrice());
        answer.put("priceSource", "BASE");
        answer.put("taxInclusive", product.inclusive());
        answer.put("subtotal", subtotal);
        answer.put("discounts", discounts);
        answer.put("discountTotal", discount);
        answer.put("discountedSubtotal", discounted);
        answer.put("taxClassCode", product.taxClass());
        answer.put("taxRate", product.taxRate());
        answer.put("net", net);
        answer.put("tax", tax);
        answer.put("lineTotal", gross);
        answer.put("currency", "KES");
        return answer;
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
