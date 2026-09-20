package com.pos.gateway;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;

import com.github.tomakehurst.wiremock.client.WireMock;

import com.pos.common.correlation.CorrelationId;

import tools.jackson.databind.JsonNode;
import tools.jackson.databind.ObjectMapper;
import tools.jackson.databind.json.JsonMapper;

/** The gateway's guarantees: routing, token validation, rate limiting and failing fast. */
class GatewayIT extends GatewayTestBase {

    // --- routing --------------------------------------------------------------

    @Test
    @DisplayName("a public route reaches the service without a token")
    void publicRouteIsProxied() {
        DOWNSTREAM.stubFor(
                WireMock.post(WireMock.urlEqualTo("/api/v1/auth/login"))
                        .willReturn(
                                WireMock.aResponse()
                                        .withStatus(200)
                                        .withHeader("Content-Type", "application/json")
                                        .withBody("{\"accessToken\":\"stub\"}")));

        ResponseEntity<String> response =
                post("/api/v1/auth/login", "{\"email\":\"a@b.c\",\"password\":\"x\"}", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);
        assertThat(response.getBody()).contains("accessToken");
        DOWNSTREAM.verify(WireMock.postRequestedFor(WireMock.urlEqualTo("/api/v1/auth/login")));
    }

    @Test
    @DisplayName("an authenticated request is forwarded with its bearer token and correlation id")
    void authenticatedRequestIsProxiedWithContext() {
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("[]")));

        String token = tokenFor(UUID.randomUUID(), 1, List.of("user:view"));
        ResponseEntity<String> response = get("/api/v1/users", token);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.OK);

        // The service re-validates the token itself, so it must arrive intact - the gateway is not
        // the only line of defence.
        DOWNSTREAM.verify(
                WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/users"))
                        .withHeader("Authorization", WireMock.equalTo("Bearer " + token))
                        // Without this the sale would appear in the logs as unrelated operations.
                        .withHeader(CorrelationId.HEADER, WireMock.matching("[A-Za-z0-9._-]+")));
    }

    @Test
    void aSuppliedCorrelationIdIsPassedThroughUnchanged() {
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("[]")));

        HttpHeaders headers = new HttpHeaders();
        headers.setBearerAuth(tokenFor(UUID.randomUUID(), 1, List.of("user:view")));
        headers.set(CorrelationId.HEADER, "trace-from-the-till");

        rest.exchange(
                baseUrl + "/api/v1/users",
                HttpMethod.GET,
                new org.springframework.http.HttpEntity<>(headers),
                String.class);

        DOWNSTREAM.verify(
                WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/users"))
                        .withHeader(CorrelationId.HEADER, WireMock.equalTo("trace-from-the-till")));
    }

    // --- token validation at the edge -----------------------------------------

    @Test
    @DisplayName("a protected route without a token is refused at the edge")
    void unauthenticatedRequestIsRefused() {
        ResponseEntity<String> response = get("/api/v1/users", null);

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        MediaType contentType = response.getHeaders().getContentType();
        assertThat(contentType).isNotNull();
        assertThat(MediaType.APPLICATION_PROBLEM_JSON.isCompatibleWith(contentType)).isTrue();
        // UTF-8 explicitly: the servlet default would mangle non-ASCII text in a message.
        assertThat(contentType.getCharset()).isEqualTo(java.nio.charset.StandardCharsets.UTF_8);
        assertThat(json(response).get("code").asString()).isEqualTo("auth.unauthenticated");
        // The request never reached the service.
        DOWNSTREAM.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/users")));
    }

    @Test
    @DisplayName("a token signed by an unknown key is refused")
    void tokenWithAnUnknownSigningKeyIsRefused() {
        ResponseEntity<String> response =
                get("/api/v1/users", tokenSignedByAnUnknownKey(UUID.randomUUID()));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        DOWNSTREAM.verify(0, WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/users")));
    }

    @Test
    void tamperedTokenIsRefused() {
        String token = tokenFor(UUID.randomUUID(), 1, List.of("user:view"));
        // Flip the payload: the signature no longer matches.
        String[] parts = token.split("\\.");
        String tampered =
                parts[0] + "." + parts[1].substring(0, parts[1].length() - 4) + "AAAA." + parts[2];

        assertThat(get("/api/v1/users", tampered).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    void expiredTokenIsRefused() {
        String expired =
                token(
                        UUID.randomUUID(),
                        1,
                        List.of("user:view"),
                        ISSUER,
                        Instant.now().minusSeconds(120));

        assertThat(get("/api/v1/users", expired).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    @Test
    @DisplayName("a token from another issuer is refused even though the signature is valid")
    void tokenFromAnotherIssuerIsRefused() {
        String wrongIssuer =
                token(
                        UUID.randomUUID(),
                        1,
                        List.of("user:view"),
                        "http://someone-elses-idp",
                        Instant.now().plusSeconds(900));

        assertThat(get("/api/v1/users", wrongIssuer).getStatusCode())
                .isEqualTo(HttpStatus.UNAUTHORIZED);
    }

    // --- token version enforcement (the Phase 3 gap) --------------------------

    @Test
    @DisplayName("a token whose version has been superseded is refused")
    void supersededTokenIsRefused() {
        UUID userId = UUID.randomUUID();
        String token = tokenFor(userId, 1, List.of("user:view"));

        // Works while it is current.
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("[]")));
        assertThat(get("/api/v1/users", token).getStatusCode()).isEqualTo(HttpStatus.OK);

        // auth-service bumps the version - a demotion, a suspension, a password change.
        publishTokenVersion(userId, 2);

        ResponseEntity<String> response = get("/api/v1/users", token);
        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.UNAUTHORIZED);
        // A distinct code, so a client knows to refresh rather than assume the session is lost.
        assertThat(json(response).get("code").asString()).isEqualTo("auth.token_superseded");
        // And it did not reach the service carrying its stale permissions.
        DOWNSTREAM.verify(1, WireMock.getRequestedFor(WireMock.urlEqualTo("/api/v1/users")));
    }

    @Test
    void aTokenMatchingThePublishedVersionIsAccepted() {
        UUID userId = UUID.randomUUID();
        publishTokenVersion(userId, 3);

        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("[]")));

        assertThat(get("/api/v1/users", tokenFor(userId, 3, List.of("user:view"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
        // A newer token than the cache knows about is fine too.
        assertThat(get("/api/v1/users", tokenFor(userId, 4, List.of("user:view"))).getStatusCode())
                .isEqualTo(HttpStatus.OK);
    }

    // --- rate limiting --------------------------------------------------------

    @Test
    @DisplayName("credential endpoints are rate limited per IP and answer 429 with Retry-After")
    void credentialEndpointsAreRateLimited() {
        DOWNSTREAM.stubFor(
                WireMock.post(WireMock.urlEqualTo("/api/v1/auth/login"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("{}")));

        String body = "{\"email\":\"a@b.c\",\"password\":\"x\"}";
        int allowed = 0;
        ResponseEntity<String> limited = null;

        // Capacity is 10 per minute for these paths.
        for (int i = 0; i < 15; i++) {
            ResponseEntity<String> response = post("/api/v1/auth/login", body, null);
            if (response.getStatusCode() == HttpStatus.TOO_MANY_REQUESTS) {
                limited = response;
                break;
            }
            allowed++;
        }

        assertThat(allowed).isEqualTo(10);
        assertThat(limited).isNotNull();
        assertThat(json(limited).get("code").asString()).isEqualTo("rate_limit.exceeded");
        // Without Retry-After a client retries immediately and makes the overload worse.
        assertThat(limited.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isNotNull();
        assertThat(limited.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("0");
    }

    @Test
    @DisplayName("authenticated traffic gets a far larger allowance than a credential endpoint")
    void authenticatedTrafficIsNotThrottledLikeLogin() {
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("[]")));

        String token = tokenFor(UUID.randomUUID(), 1, List.of("user:view"));

        // Comfortably past the credential limit: a busy lane must not be throttled at 10 requests.
        for (int i = 0; i < 30; i++) {
            assertThat(get("/api/v1/users", token).getStatusCode()).isEqualTo(HttpStatus.OK);
        }

        ResponseEntity<String> last = get("/api/v1/users", token);
        assertThat(last.getHeaders().getFirst("X-RateLimit-Limit")).isEqualTo("300");
    }

    @Test
    void rateLimitBucketsAreSeparatePerUser() {
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("[]")));

        String first = tokenFor(UUID.randomUUID(), 1, List.of("user:view"));
        String second = tokenFor(UUID.randomUUID(), 1, List.of("user:view"));

        get("/api/v1/users", first);
        ResponseEntity<String> secondUser = get("/api/v1/users", second);

        // One cashier's traffic must not spend another's allowance.
        assertThat(secondUser.getHeaders().getFirst("X-RateLimit-Remaining")).isEqualTo("299");
    }

    @Test
    void healthProbesAreNeverRateLimited() {
        // An orchestrator must not conclude the gateway is dead because it is shedding load.
        for (int i = 0; i < 20; i++) {
            assertThat(get("/actuator/health", null).getStatusCode()).isEqualTo(HttpStatus.OK);
        }
    }

    // --- failing fast ---------------------------------------------------------

    @Test
    @DisplayName("an unreachable service answers 503 immediately rather than hanging")
    void unreachableServiceFailsFast() {
        String token = tokenFor(UUID.randomUUID(), 1, List.of("product:view"));

        long start = System.currentTimeMillis();
        ResponseEntity<String> response = get("/api/v1/products/anything", token);
        long elapsed = System.currentTimeMillis() - start;

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.SERVICE_UNAVAILABLE);
        assertThat(json(response).get("code").asString()).isEqualTo("service.unavailable");
        assertThat(response.getHeaders().getFirst(HttpHeaders.RETRY_AFTER)).isEqualTo("5");
        // A till has to learn quickly that it should switch to offline mode.
        assertThat(elapsed).isLessThan(10_000);
    }

    @Test
    @DisplayName("a downstream 500 is passed through, not disguised as a gateway error")
    void downstreamErrorsArePassedThrough() {
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(WireMock.aResponse().withStatus(500).withBody("{}")));

        ResponseEntity<String> response =
                get("/api/v1/users", tokenFor(UUID.randomUUID(), 1, List.of("user:view")));

        assertThat(response.getStatusCode()).isEqualTo(HttpStatus.INTERNAL_SERVER_ERROR);
    }

    // --- browser-facing concerns ----------------------------------------------

    @Test
    void corsPreflightIsAnsweredForTheAllowedOrigin() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("http://localhost:3000");
        headers.setAccessControlRequestMethod(HttpMethod.POST);
        headers.setAccessControlRequestHeaders(List.of("Authorization", "Content-Type"));

        ResponseEntity<String> response =
                rest.exchange(
                        baseUrl + "/api/v1/auth/login",
                        HttpMethod.OPTIONS,
                        new org.springframework.http.HttpEntity<>(headers),
                        String.class);

        assertThat(response.getStatusCode().is2xxSuccessful()).isTrue();
        assertThat(response.getHeaders().getAccessControlAllowOrigin())
                .isEqualTo("http://localhost:3000");
        assertThat(response.getHeaders().getAccessControlAllowCredentials()).isTrue();
        // The BFF needs to read these off the response.
        assertThat(response.getHeaders().getAccessControlExposeHeaders())
                .contains("X-Correlation-Id", "Retry-After");
    }

    @Test
    void anUnknownOriginIsNotGrantedCors() {
        HttpHeaders headers = new HttpHeaders();
        headers.setOrigin("https://evil.example");
        headers.setAccessControlRequestMethod(HttpMethod.POST);

        ResponseEntity<String> response =
                rest.exchange(
                        baseUrl + "/api/v1/auth/login",
                        HttpMethod.OPTIONS,
                        new org.springframework.http.HttpEntity<>(headers),
                        String.class);

        assertThat(response.getHeaders().getAccessControlAllowOrigin()).isNull();
    }

    @Test
    void securityHeadersArePresent() {
        DOWNSTREAM.stubFor(
                WireMock.post(WireMock.urlEqualTo("/api/v1/auth/login"))
                        .willReturn(WireMock.aResponse().withStatus(200).withBody("{}")));

        HttpHeaders headers = post("/api/v1/auth/login", "{}", null).getHeaders();

        assertThat(headers.getFirst("X-Content-Type-Options")).isEqualTo("nosniff");
        assertThat(headers.getFirst("X-Frame-Options")).isEqualTo("DENY");
        assertThat(headers.getFirst("Referrer-Policy"))
                .isEqualTo("strict-origin-when-cross-origin");
        // HSTS is deliberately absent over plain HTTP: Spring Security only emits it on a secure
        // request, which is right, since the header means nothing to a client that did not arrive
        // over TLS. Production terminates TLS at Traefik in front of the gateway.
        assertThat(headers.getFirst("Strict-Transport-Security")).isNull();
    }

    @Test
    @DisplayName("the correlation header appears once, not once per hop")
    void correlationHeaderIsNotDuplicated() {
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/api/v1/users"))
                        .willReturn(
                                WireMock.aResponse()
                                        .withStatus(200)
                                        // The service echoes the id it was given, as ours do.
                                        .withHeader(CorrelationId.HEADER, "echoed-by-the-service")
                                        .withBody("[]")));

        ResponseEntity<String> response =
                get("/api/v1/users", tokenFor(UUID.randomUUID(), 1, List.of("user:view")));

        assertThat(response.getHeaders().get(CorrelationId.HEADER)).hasSize(1);
    }

    @Test
    void everyResponseCarriesACorrelationId() {
        assertThat(get("/api/v1/users", null).getHeaders().getFirst(CorrelationId.HEADER))
                .matches("[A-Za-z0-9._-]+");
    }

    // The gateway has no reason to depend on events-lib, so the test parses JSON itself.
    private static final ObjectMapper MAPPER = JsonMapper.builder().build();

    private static JsonNode json(ResponseEntity<String> response) {
        return MAPPER.readTree(response.getBody());
    }
}
