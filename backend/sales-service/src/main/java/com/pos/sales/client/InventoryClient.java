package com.pos.sales.client;

import java.math.BigDecimal;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.cloud.client.circuitbreaker.CircuitBreakerFactory;
import org.springframework.http.HttpHeaders;
import org.springframework.stereotype.Component;
import org.springframework.web.client.RestClient;

import com.pos.common.correlation.CorrelationId;

import lombok.RequiredArgsConstructor;

/**
 * Holds and releases stock for a basket in progress.
 *
 * <p>A soft reservation, not a deduction: the goods are promised to this cart so a second lane
 * cannot promise the last one, and the actual deduction happens when {@code sale-completed} reaches
 * inventory.
 *
 * <p>Failure here is deliberately not fatal. Reserving is an optimisation against overselling the
 * last unit; refusing a checkout because inventory is briefly unreachable would stop a shop trading
 * over something it can reconcile afterwards - and inventory already records and alerts on stock
 * that goes negative. Every method therefore reports success rather than throwing, and the caller
 * carries on.
 */
@Component
@RequiredArgsConstructor
public class InventoryClient {

    private static final Logger log = LoggerFactory.getLogger(InventoryClient.class);
    private static final String BREAKER = "inventory-reservations";

    private final RestClient inventoryRestClient;
    private final CircuitBreakerFactory<?, ?> circuitBreakers;

    /**
     * @return true when the hold was placed
     */
    public boolean reserve(
            UUID productId, UUID branchId, BigDecimal quantity, UUID cartId, String authorization) {

        // Captured here, on the request thread: inside the breaker the MDC is empty.
        String correlationId = CorrelationId.get();
        return circuitBreakers
                .create(BREAKER)
                .run(
                        () -> {
                            inventoryRestClient
                                    .post()
                                    .uri("/api/v1/reservations")
                                    .headers(
                                            headers ->
                                                    authorize(
                                                            headers, authorization, correlationId))
                                    .body(
                                            new ReserveRequest(
                                                    productId, branchId, quantity, "Cart", cartId))
                                    .retrieve()
                                    .toBodilessEntity();
                            return true;
                        },
                        throwable -> {
                            // Logged, not thrown: see the class comment. Overselling the last unit
                            // is a smaller problem than a lane that will not serve anyone.
                            log.warn(
                                    "Could not reserve {} of {} for cart {}: {}",
                                    quantity,
                                    productId,
                                    cartId,
                                    throwable.toString());
                            return false;
                        });
    }

    /**
     * Releases everything held for a cart. Called on cancellation and after completion.
     *
     * <p>Skipped entirely with no token, which is the case on the scheduled timeout sweep: there is
     * no caller to borrow credentials from, and inventory expires a stale reservation on its own
     * sweep anyway. Making the call regardless would mean a guaranteed 401 every minute.
     */
    public boolean release(UUID cartId, String authorization) {
        if (authorization == null) {
            log.debug(
                    "No token to release reservations for cart {}; inventory will expire them",
                    cartId);
            return false;
        }
        String correlationId = CorrelationId.get();
        return circuitBreakers
                .create(BREAKER)
                .run(
                        () -> {
                            inventoryRestClient
                                    .delete()
                                    .uri(
                                            uriBuilder ->
                                                    uriBuilder
                                                            .path("/api/v1/reservations")
                                                            .queryParam("referenceType", "Cart")
                                                            .queryParam("referenceId", cartId)
                                                            .build())
                                    .headers(
                                            headers ->
                                                    authorize(
                                                            headers, authorization, correlationId))
                                    .retrieve()
                                    .toBodilessEntity();
                            return true;
                        },
                        throwable -> {
                            // A stranded reservation expires on inventory's own sweep, so this is
                            // recoverable without intervention.
                            log.warn(
                                    "Could not release reservations for cart {}: {}",
                                    cartId,
                                    throwable.toString());
                            return false;
                        });
    }

    private static void authorize(HttpHeaders headers, String authorization, String correlationId) {
        if (authorization != null) {
            headers.set(HttpHeaders.AUTHORIZATION, authorization);
        }
        if (correlationId != null) {
            headers.set(CorrelationId.HEADER, correlationId);
        }
    }

    private record ReserveRequest(
            UUID productId,
            UUID branchId,
            BigDecimal quantity,
            String referenceType,
            UUID referenceId) {}
}
