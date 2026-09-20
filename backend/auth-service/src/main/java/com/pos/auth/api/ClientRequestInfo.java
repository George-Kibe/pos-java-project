package com.pos.auth.api;

import jakarta.servlet.http.HttpServletRequest;

/**
 * Extracts caller details for the audit trail and lockout counters.
 *
 * <p>{@code X-Forwarded-For} is honoured because in production requests arrive through Traefik and
 * the gateway, so {@code getRemoteAddr()} would record the proxy on every request and make per-IP
 * limits useless. The header is only trustworthy because nothing but our own proxy can reach these
 * ports - if that ever changes, a client could spoof it to dodge rate limits.
 */
public final class ClientRequestInfo {

    private ClientRequestInfo() {}

    private static final int MAX_LENGTH = 45; // an IPv6 address at its longest

    public static String ipOf(HttpServletRequest request) {
        String forwarded = request.getHeader("X-Forwarded-For");
        if (forwarded != null && !forwarded.isBlank()) {
            // Left-most entry is the original client; the rest are proxies.
            String first = forwarded.split(",")[0].trim();
            if (!first.isEmpty()) {
                return truncate(first);
            }
        }
        return truncate(request.getRemoteAddr());
    }

    public static String userAgentOf(HttpServletRequest request) {
        String agent = request.getHeader("User-Agent");
        return agent == null || agent.length() <= 255 ? agent : agent.substring(0, 255);
    }

    private static String truncate(String value) {
        return value != null && value.length() > MAX_LENGTH
                ? value.substring(0, MAX_LENGTH)
                : value;
    }
}
