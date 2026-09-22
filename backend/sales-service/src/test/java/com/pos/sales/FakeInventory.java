package com.pos.sales;

import java.io.IOException;
import java.net.InetSocketAddress;
import java.util.List;
import java.util.concurrent.CopyOnWriteArrayList;

import com.sun.net.httpserver.HttpExchange;
import com.sun.net.httpserver.HttpServer;

/**
 * Inventory's reservation endpoint, recording what it was asked.
 *
 * <p>The assertions that matter are about the calls: a checkout reserves, a failed payment
 * releases, and a completed sale leaves the deduction to the event rather than to this API.
 */
final class FakeInventory {

    record Call(String method, String query) {}

    private final HttpServer server;
    private final List<Call> calls = new CopyOnWriteArrayList<>();

    FakeInventory() {
        try {
            server = HttpServer.create(new InetSocketAddress("127.0.0.1", 0), 0);
        } catch (IOException e) {
            throw new IllegalStateException(e);
        }
        server.createContext("/api/v1/reservations", this::handle);
        server.start();
    }

    String baseUrl() {
        return "http://127.0.0.1:" + server.getAddress().getPort();
    }

    void reset() {
        calls.clear();
    }

    List<Call> calls() {
        return List.copyOf(calls);
    }

    long reservations() {
        return calls.stream().filter(call -> call.method().equals("POST")).count();
    }

    /** Releases for one cart, identified by the reference id in the query string. */
    long releasesFor(java.util.UUID cartId) {
        return calls.stream()
                .filter(call -> call.method().equals("DELETE"))
                .filter(call -> call.query() != null && call.query().contains(cartId.toString()))
                .count();
    }

    private void handle(HttpExchange exchange) throws IOException {
        exchange.getRequestBody().readAllBytes();
        calls.add(new Call(exchange.getRequestMethod(), exchange.getRequestURI().getQuery()));
        byte[] body = "{\"status\":\"HELD\",\"released\":1}".getBytes();
        exchange.getResponseHeaders().set("Content-Type", "application/json");
        exchange.sendResponseHeaders(200, body.length);
        exchange.getResponseBody().write(body);
        exchange.close();
    }
}
