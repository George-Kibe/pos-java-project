package com.pos.sales.client;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.core.ParameterizedTypeReference;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.HttpClientErrorException;
import org.springframework.web.client.RestClient;

import com.pos.common.correlation.CorrelationId;
import com.pos.common.error.Errors;

import lombok.RequiredArgsConstructor;

/**
 * Asks catalog what things cost.
 *
 * <p>Sales does not price anything itself. Catalog owns the tax engine, the price lists and the
 * promotions, and the roadmap requires a receipt whose breakdown matches catalog's exactly - which
 * is only guaranteed if there is one implementation and this service carries its answers.
 *
 * <p>Behind a circuit breaker, because a slow catalog must not hold a checkout lane open. When it
 * trips, the caller decides what to do: a live sale is refused with a clear message, while an
 * offline batch being replayed falls back to the prices the terminal recorded and flags the sale.
 *
 * <p>The caller's own token is forwarded rather than a service credential. The cashier already
 * holds {@code product:view}, so there is no new secret to invent or rotate, and catalog sees who
 * is really asking.
 */
@Component
@RequiredArgsConstructor
public class CatalogPricingClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogPricingClient.class);
    private static final String BREAKER = "catalog-pricing";

    private final RestClient catalogRestClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    /** One line to be priced. Either a product id or a barcode identifies it. */
    public record LineToPrice(UUID productId, String sku, String barcode, BigDecimal quantity) {}

    /**
     * Prices a basket.
     *
     * @param authorization the caller's bearer token, forwarded so catalog can authorise the call
     * @param at prices as at this instant; null means now. An offline sale is repriced at *now*
     *     deliberately, because the question being asked is what it should have cost.
     * @throws Errors.ServiceUnavailableException when catalog cannot be reached
     */
    public List<PricedLineResponse> price(
            List<LineToPrice> lines,
            UUID branchId,
            boolean member,
            Instant at,
            String authorization) {

        if (lines.isEmpty()) {
            return List.of();
        }

        // Captured here, on the request thread: inside the breaker the MDC is empty.
        String correlationId = CorrelationId.get();

        return circuitBreakers
                .create(BREAKER)
                .run(
                        () -> post(lines, branchId, member, at, authorization, correlationId),
                        throwable -> {
                            if (throwable instanceof HttpClientErrorException refused) {
                                // Catalog answered, and the answer was no: an unknown barcode, an
                                // inactive product. Reporting that as an outage would send a till
                                // into retrying a request that can never succeed.
                                throw refusal(refused);
                            }
                            log.warn("Catalog pricing unavailable: {}", throwable.toString());
                            throw new Errors.ServiceUnavailableException(
                                    "catalog.unavailable",
                                    "Prices cannot be confirmed right now. The sale has not been"
                                            + " recorded; try again.");
                        });
    }

    private static RuntimeException refusal(HttpClientErrorException refused) {
        String detail = refused.getResponseBodyAsString();
        if (refused.getStatusCode().value() == 404) {
            return new Errors.NotFoundException(
                    "catalog.product_not_found",
                    "Catalog does not know one of these products, so it cannot be sold");
        }
        log.info("Catalog refused to price: {} {}", refused.getStatusCode(), detail);
        return new Errors.BusinessRuleException(
                "catalog.pricing_refused", "Catalog refused to price this line");
    }

    private List<PricedLineResponse> post(
            List<LineToPrice> lines,
            UUID branchId,
            boolean member,
            Instant at,
            String authorization,
            String correlationId) {

        PriceRequest body = new PriceRequest(lines, branchId, member, at);

        return catalogRestClient
                .post()
                .uri("/api/v1/pricing/resolve")
                .headers(
                        headers -> {
                            if (authorization != null) {
                                headers.set(HttpHeaders.AUTHORIZATION, authorization);
                            }
                            if (correlationId != null) {
                                headers.set(CorrelationId.HEADER, correlationId);
                            }
                        })
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<List<PricedLineResponse>>() {});
    }

    /** Catalog's request shape. */
    private record PriceRequest(
            List<LineToPrice> lines, UUID branchId, boolean member, Instant at) {}
}
