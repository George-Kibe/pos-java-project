package com.pos.common.correlation;

import java.io.IOException;
import java.util.regex.Pattern;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ServletException;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletResponse;

import org.springframework.core.Ordered;
import org.springframework.core.annotation.Order;
import org.springframework.web.filter.OncePerRequestFilter;

/**
 * Accepts an inbound correlation id or generates one, publishes it to the MDC for the duration of
 * the request, and echoes it on the response so a client can quote it in a bug report.
 *
 * <p>Runs first, ahead of security, so that even a rejected request is traceable.
 *
 * <p>The inbound value is attacker-controlled, so it is sanitised rather than trusted: anything
 * outside a conservative character set, or longer than {@link #MAX_LENGTH}, is discarded and
 * replaced with a generated id. Without that, a crafted header could inject newlines into the log
 * stream and forge log entries.
 *
 * <p>The resolved id is also stored as a request attribute, not only in the MDC. The MDC is
 * thread-local and does not survive a hand-off - a circuit breaker with a timeout runs the
 * downstream call on its own thread pool, and anything reading the MDC there sees nothing. Code
 * that needs the id outside this thread reads {@link #REQUEST_ATTRIBUTE}.
 */
@Order(Ordered.HIGHEST_PRECEDENCE)
public class CorrelationIdFilter extends OncePerRequestFilter {

    /** Long enough for a UUID or a trace id, short enough not to bloat every log line. */
    public static final int MAX_LENGTH = 64;

    /** Request attribute holding the resolved id, for code running off the request thread. */
    public static final String REQUEST_ATTRIBUTE = CorrelationIdFilter.class.getName() + ".id";

    private static final Pattern SAFE = Pattern.compile("^[A-Za-z0-9._-]+$");

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String correlationId = sanitise(request.getHeader(CorrelationId.HEADER));
        try {
            CorrelationId.set(correlationId);
            request.setAttribute(REQUEST_ATTRIBUTE, correlationId);
            response.setHeader(CorrelationId.HEADER, correlationId);
            chain.doFilter(request, response);
        } finally {
            // Servlet threads are pooled; leaving the value behind would mislabel the next request.
            CorrelationId.clear();
        }
    }

    private static String sanitise(String candidate) {
        if (candidate == null
                || candidate.isBlank()
                || candidate.length() > MAX_LENGTH
                || !SAFE.matcher(candidate).matches()) {
            return CorrelationId.generate();
        }
        return candidate;
    }
}
