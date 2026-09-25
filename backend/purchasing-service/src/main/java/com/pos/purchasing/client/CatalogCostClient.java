package com.pos.purchasing.client;

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
import com.pos.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/**
 * Asks catalog what tax each product carries, and so what a cost is without it.
 *
 * <p>Catalog owns the tax engine; taking VAT out of a supplier's price is done there, once, rather
 * than by a second copy of the rates here. The caller's own token is forwarded - whoever keys in a
 * delivery can view products - so there is no service credential to invent.
 */
@Component
@RequiredArgsConstructor
public class CatalogCostClient {

    private static final Logger log = LoggerFactory.getLogger(CatalogCostClient.class);
    private static final String BREAKER = "catalog-costs";

    private final RestClient catalogRestClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    public record CostLine(UUID productId, BigDecimal unitCost) {}

    /** One product's cost as keyed in, its tax rate, and the cost without tax. */
    public record NetCost(UUID productId, BigDecimal taxRate, BigDecimal netUnitCost) {}

    /**
     * @param costsIncludeTax whether the costs keyed in carry VAT
     * @throws Errors.ServiceUnavailableException when catalog cannot be reached: nothing is
     *     recorded, and trying again is worth it
     */
    public List<NetCost> netCosts(
            UUID branchId, Instant at, boolean costsIncludeTax, List<CostLine> lines) {
        if (lines.isEmpty()) {
            return List.of();
        }
        // Read here, on the request thread: the breaker runs the call on its own.
        String correlationId = CorrelationId.get();
        String token = AuthenticatedUser.bearerToken().orElse(null);
        return circuitBreakers
                .create(BREAKER)
                .run(
                        () ->
                                post(
                                        new Request(branchId, at, costsIncludeTax, lines),
                                        token,
                                        correlationId),
                        failure -> {
                            if (failure instanceof HttpClientErrorException refused) {
                                // Catalog answered: an unknown product, a class with no rate.
                                throw refusal(refused);
                            }
                            log.warn("Catalog unavailable for costs: {}", failure.toString());
                            throw new Errors.ServiceUnavailableException(
                                    "catalog.unavailable",
                                    "Tax rates could not be confirmed, so nothing was recorded."
                                            + " Try again in a moment.");
                        });
    }

    private static RuntimeException refusal(HttpClientErrorException refused) {
        if (refused.getStatusCode().value() == 404) {
            return new Errors.NotFoundException(
                    "catalog.product_not_found", "Catalog does not know one of these products.");
        }
        log.info("Catalog refused a cost check: {}", refused.getStatusCode());
        return new Errors.BusinessRuleException(
                "catalog.cost_check_refused",
                "Catalog could not work out the tax on one of these products.");
    }

    private List<NetCost> post(Request body, String token, String correlationId) {
        return catalogRestClient
                .post()
                .uri("/api/v1/pricing/cost-check")
                .headers(
                        headers -> {
                            if (token != null) {
                                // Already "Bearer ...": the verified token, as the caller sent it.
                                headers.set(HttpHeaders.AUTHORIZATION, token);
                            }
                            if (correlationId != null) {
                                headers.set(CorrelationId.HEADER, correlationId);
                            }
                            headers.set(HttpHeaders.ACCEPT, "application/json");
                        })
                .body(body)
                .retrieve()
                .body(new ParameterizedTypeReference<List<NetCost>>() {});
    }

    /** Catalog's request shape; its answer carries more than this client reads. */
    private record Request(
            UUID branchId, Instant at, boolean costIncludesTax, List<CostLine> lines) {}
}
