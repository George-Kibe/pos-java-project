package com.pos.gateway;

import java.security.KeyPairGenerator;
import java.security.interfaces.RSAPrivateKey;
import java.security.interfaces.RSAPublicKey;
import java.time.Instant;
import java.util.Date;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.AfterAll;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.data.redis.core.StringRedisTemplate;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

import com.github.tomakehurst.wiremock.WireMockServer;
import com.github.tomakehurst.wiremock.client.WireMock;
import com.github.tomakehurst.wiremock.core.WireMockConfiguration;
import com.nimbusds.jose.JWSAlgorithm;
import com.nimbusds.jose.JWSHeader;
import com.nimbusds.jose.crypto.RSASSASigner;
import com.nimbusds.jose.jwk.JWKSet;
import com.nimbusds.jose.jwk.RSAKey;
import com.nimbusds.jwt.JWTClaimsSet;
import com.nimbusds.jwt.SignedJWT;
import com.redis.testcontainers.RedisContainer;

import com.pos.common.security.TokenVersionKeys;

/**
 * Gateway tests in isolation: a real Redis, a WireMock stub standing in for every downstream
 * service, and a real RSA keypair whose JWKS the gateway fetches exactly as it would from
 * auth-service.
 *
 * <p>Isolating it this way is the point. Running auth-service alongside would test the two together
 * and say nothing about whether the gateway rejects a token signed by the wrong key, or one whose
 * version has been superseded - the cases that matter here and that a happy-path end-to-end test
 * cannot reach.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
public abstract class GatewayTestBase {

    protected static final String ISSUER = "http://auth-service-under-test";

    /** Started once for the JVM; see the note in auth-service's test base about @Container. */
    static final RedisContainer REDIS = new RedisContainer("redis:8-alpine");

    /** Stands in for auth-service: serves the JWKS and any downstream endpoint under test. */
    static final WireMockServer DOWNSTREAM =
            // HTTP/1.1 only, like the Tomcat services it stands in for: the JDK client offers every
            // plain-HTTP call an h2c upgrade, which Tomcat ignores but WireMock's Jetty accepts and
            // then intermittently resets - an EOF the circuit breaker reports as the service down.
            new WireMockServer(
                    WireMockConfiguration.options().dynamicPort().http2PlainDisabled(true));

    private static final RSAKey SIGNING_KEY = generateSigningKey();

    static {
        REDIS.start();
        DOWNSTREAM.start();
        // The gateway fetches this to verify signatures, just as it would from auth-service.
        DOWNSTREAM.stubFor(
                WireMock.get(WireMock.urlEqualTo("/.well-known/jwks.json"))
                        .willReturn(
                                WireMock.aResponse()
                                        .withHeader("Content-Type", "application/json")
                                        .withBody(
                                                new JWKSet(SIGNING_KEY)
                                                        .toPublicJWKSet()
                                                        .toString())));
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
        registry.add(
                "spring.security.oauth2.resourceserver.jwt.jwk-set-uri",
                () -> DOWNSTREAM.baseUrl() + "/.well-known/jwks.json");
        registry.add("pos.gateway.jwt.issuer", () -> ISSUER);
        registry.add("AUTH_SERVICE_URI", DOWNSTREAM::baseUrl);
        registry.add("PAYMENT_SERVICE_URI", DOWNSTREAM::baseUrl);
        // Deliberately nothing listening here, so the circuit breaker path is exercised.
        registry.add("CATALOG_SERVICE_URI", () -> "http://localhost:1");
    }

    @AfterAll
    static void stopStub() {
        // Redis is left running for the JVM; the stub is cheap to restart and holds per-test state.
        DOWNSTREAM.resetAll();
    }

    @LocalServerPort protected int port;

    @Autowired protected TestRestTemplate rest;
    @Autowired protected StringRedisTemplate redis;

    protected String baseUrl;

    @BeforeEach
    void setUpBase() {
        baseUrl = "http://localhost:" + port;
        // Rate limit buckets are keyed by client IP, and every test shares 127.0.0.1. Without
        // this, whichever test ran first would spend the allowance for all of them.
        redis.getConnectionFactory().getConnection().serverCommands().flushAll();
        DOWNSTREAM.resetRequests();
    }

    // --- token minting --------------------------------------------------------

    protected String tokenFor(UUID userId, int tokenVersion, List<String> permissions) {
        return token(userId, tokenVersion, permissions, ISSUER, Instant.now().plusSeconds(900));
    }

    protected String token(
            UUID userId,
            int tokenVersion,
            List<String> permissions,
            String issuer,
            Instant expiresAt) {
        try {
            JWTClaimsSet claims =
                    new JWTClaimsSet.Builder()
                            .issuer(issuer)
                            .subject(userId.toString())
                            .claim("uid", userId.toString())
                            .claim("email", "test@pos.test")
                            .claim("roles", List.of("CASHIER"))
                            .claim("perms", permissions)
                            .claim("branches", List.of())
                            .claim("tv", tokenVersion)
                            .jwtID(UUID.randomUUID().toString())
                            .issueTime(Date.from(Instant.now().minusSeconds(5)))
                            .expirationTime(Date.from(expiresAt))
                            .build();

            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .keyID(SIGNING_KEY.getKeyID())
                                    .build(),
                            claims);
            jwt.sign(new RSASSASigner(SIGNING_KEY.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException("Could not mint a test token", e);
        }
    }

    /** A token signed by a key the gateway has never seen. */
    protected String tokenSignedByAnUnknownKey(UUID userId) {
        try {
            RSAKey other = generateSigningKey();
            JWTClaimsSet claims =
                    new JWTClaimsSet.Builder()
                            .issuer(ISSUER)
                            .subject(userId.toString())
                            .claim("uid", userId.toString())
                            .claim("tv", 1)
                            .expirationTime(Date.from(Instant.now().plusSeconds(900)))
                            .build();
            SignedJWT jwt =
                    new SignedJWT(
                            new JWSHeader.Builder(JWSAlgorithm.RS256)
                                    .keyID(other.getKeyID())
                                    .build(),
                            claims);
            jwt.sign(new RSASSASigner(other.toRSAPrivateKey()));
            return jwt.serialize();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }

    protected void publishTokenVersion(UUID userId, int version) {
        redis.opsForValue().set(TokenVersionKeys.forUser(userId), String.valueOf(version));
    }

    // --- HTTP helpers ---------------------------------------------------------

    protected ResponseEntity<String> get(String path, String token) {
        return rest.exchange(baseUrl + path, HttpMethod.GET, entity(null, token), String.class);
    }

    protected ResponseEntity<String> post(String path, String body, String token) {
        return rest.exchange(baseUrl + path, HttpMethod.POST, entity(body, token), String.class);
    }

    private HttpEntity<Object> entity(Object body, String token) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (token != null) {
            headers.setBearerAuth(token);
        }
        return new HttpEntity<>(body, headers);
    }

    private static RSAKey generateSigningKey() {
        try {
            KeyPairGenerator generator = KeyPairGenerator.getInstance("RSA");
            generator.initialize(2048);
            var pair = generator.generateKeyPair();
            return new RSAKey.Builder((RSAPublicKey) pair.getPublic())
                    .privateKey((RSAPrivateKey) pair.getPrivate())
                    .keyID(UUID.randomUUID().toString())
                    .build();
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
    }
}
