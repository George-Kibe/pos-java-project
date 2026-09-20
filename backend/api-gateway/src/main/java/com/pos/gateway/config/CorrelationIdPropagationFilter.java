package com.pos.gateway.config;

import org.springframework.cloud.gateway.server.mvc.filter.HttpHeadersFilter;
import org.springframework.http.HttpHeaders;
import org.springframework.web.servlet.function.ServerRequest;

import com.pos.common.correlation.CorrelationId;
import com.pos.common.correlation.CorrelationIdFilter;

/**
 * Puts the correlation id on every request the gateway forwards.
 *
 * <p>common-lib's filter establishes the id and returns it to the caller, but that alone stops at
 * the gateway: without this, each downstream service would generate an id of its own and a single
 * sale would appear in the logs as half a dozen unrelated operations.
 *
 * <p>The id is read from the request attribute rather than the MDC, and that detail matters. The
 * circuit breaker enforces its timeout by running the downstream call on its own thread pool, and
 * the MDC is thread-local - reading it there produced a freshly generated id on every proxied
 * request, quietly breaking the very tracing this filter exists to provide. The request attribute
 * travels with the request instead of the thread.
 */
public class CorrelationIdPropagationFilter implements HttpHeadersFilter.RequestHttpHeadersFilter {

    /**
     * Drops the downstream service's own correlation header from the response.
     *
     * <p>The service echoes the id it was given, and the gateway has already set the same value, so
     * without this the caller receives {@code X-Correlation-Id} twice. The values agree, which is
     * why it is only untidy rather than wrong - but a duplicated header is the kind of thing a
     * strict client or a proxy further along will eventually object to.
     */
    public static class ResponseDeduplication
            implements HttpHeadersFilter.ResponseHttpHeadersFilter {

        @Override
        public HttpHeaders apply(
                HttpHeaders headers,
                org.springframework.web.servlet.function.ServerResponse response) {
            if (headers.getFirst(CorrelationId.HEADER) == null) {
                return headers;
            }
            HttpHeaders cleaned = new HttpHeaders();
            cleaned.addAll(headers);
            cleaned.remove(CorrelationId.HEADER);
            return cleaned;
        }
    }

    @Override
    public HttpHeaders apply(HttpHeaders headers, ServerRequest request) {
        HttpHeaders forwarded = new HttpHeaders();
        forwarded.addAll(headers);

        String correlationId = resolve(request);
        if (correlationId != null) {
            forwarded.set(CorrelationId.HEADER, correlationId);
        }
        return forwarded;
    }

    private static String resolve(ServerRequest request) {
        Object fromAttribute = request.attributes().get(CorrelationIdFilter.REQUEST_ATTRIBUTE);
        if (fromAttribute instanceof String id && !id.isBlank()) {
            return id;
        }
        // Falls back to the MDC, which is correct whenever the forward happens on the request
        // thread, and then to the inbound header.
        String fromMdc = CorrelationId.get();
        if (fromMdc != null && !fromMdc.isBlank()) {
            return fromMdc;
        }
        return request.headers().firstHeader(CorrelationId.HEADER);
    }
}
