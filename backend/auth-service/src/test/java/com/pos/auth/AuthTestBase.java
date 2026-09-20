package com.pos.auth;

import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.resttestclient.TestRestTemplate;
import org.springframework.boot.resttestclient.autoconfigure.AutoConfigureTestRestTemplate;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.web.server.LocalServerPort;
import org.springframework.http.HttpEntity;
import org.springframework.http.HttpHeaders;
import org.springframework.http.HttpMethod;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.redis.testcontainers.RedisContainer;

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.auth.OtpRequestedPayload;
import com.pos.events.auth.PasswordResetRequestedPayload;

/**
 * Shared setup for the auth integration tests: a real PostgreSQL, the service running over HTTP on
 * a random port, and helpers for the things a test needs that a user would get by email.
 *
 * <p>Tests go through HTTP rather than calling services directly, so the security filter chain,
 * validation and error rendering are all exercised - the parts most likely to be wrong in a way
 * unit tests cannot see.
 */
@SpringBootTest(webEnvironment = SpringBootTest.WebEnvironment.RANDOM_PORT)
// Boot 4 no longer registers TestRestTemplate implicitly; it is opt-in.
@AutoConfigureTestRestTemplate
@ActiveProfiles("test")
public abstract class AuthTestBase {

    /**
     * Started once for the whole JVM and never stopped, rather than with {@code @Container}.
     *
     * <p>The annotation ties the container's life to a single test class, so the second test class
     * gets a fresh container on a new port while Spring's context cache still hands out the context
     * built for the first one - every request then fails against a dead port, with a 500 that says
     * nothing about why. Ryuk removes this container when the JVM exits.
     */
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    /** Token-version publications go here, and the gateway reads them from the same key. */
    static final RedisContainer REDIS = new RedisContainer("redis:7-alpine");

    static {
        POSTGRES.start();
        REDIS.start();
    }

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.data.redis.host", REDIS::getHost);
        registry.add("spring.data.redis.port", () -> REDIS.getFirstMappedPort());
    }

    @LocalServerPort protected int port;

    @Autowired protected TestRestTemplate rest;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected org.springframework.data.redis.core.StringRedisTemplate redis;

    protected String baseUrl;

    @BeforeEach
    void setUpBase() {
        baseUrl = "http://localhost:" + port;
    }

    // --- HTTP helpers ---------------------------------------------------------

    protected ResponseEntity<String> post(String path, Object body) {
        return rest.exchange(baseUrl + path, HttpMethod.POST, jsonEntity(body, null), String.class);
    }

    protected ResponseEntity<String> post(String path, Object body, String accessToken) {
        return rest.exchange(
                baseUrl + path, HttpMethod.POST, jsonEntity(body, accessToken), String.class);
    }

    protected ResponseEntity<String> get(String path, String accessToken) {
        return rest.exchange(
                baseUrl + path, HttpMethod.GET, jsonEntity(null, accessToken), String.class);
    }

    protected ResponseEntity<String> put(String path, Object body, String accessToken) {
        return rest.exchange(
                baseUrl + path, HttpMethod.PUT, jsonEntity(body, accessToken), String.class);
    }

    protected ResponseEntity<String> patch(String path, Object body, String accessToken) {
        // TestRestTemplate's default client cannot do PATCH, so it is spelled out via exchange
        // with the method override the Apache client understands.
        return rest.exchange(
                baseUrl + path, HttpMethod.PATCH, jsonEntity(body, accessToken), String.class);
    }

    private HttpEntity<Object> jsonEntity(Object body, String accessToken) {
        HttpHeaders headers = new HttpHeaders();
        headers.setContentType(MediaType.APPLICATION_JSON);
        if (accessToken != null) {
            headers.setBearerAuth(accessToken);
        }
        return new HttpEntity<>(body, headers);
    }

    // --- things a real user would receive by email ----------------------------

    /**
     * Reads the OTP out of the outbox.
     *
     * <p>This is the same row the notification service will consume in Phase 5, so the test proves
     * the event is produced correctly rather than reaching into service internals.
     */
    protected String latestOtpFor(String email) {
        String payload = latestOutboxPayload(Topics.AUTH_OTP_REQUESTED);
        EventEnvelope<OtpRequestedPayload> envelope =
                EventJson.readEnvelope(payload, OtpRequestedPayload.class);
        if (!envelope.payload().email().equalsIgnoreCase(email.trim())) {
            throw new AssertionError(
                    "Latest OTP event is for %s, not %s"
                            .formatted(envelope.payload().email(), email));
        }
        return envelope.payload().otpCode();
    }

    protected String latestPasswordResetTokenFor(String email) {
        String payload = latestOutboxPayload(Topics.AUTH_PASSWORD_RESET_REQUESTED);
        EventEnvelope<PasswordResetRequestedPayload> envelope =
                EventJson.readEnvelope(payload, PasswordResetRequestedPayload.class);
        if (!envelope.payload().email().equalsIgnoreCase(email.trim())) {
            throw new AssertionError("Latest reset event is for a different address");
        }
        return envelope.payload().resetToken();
    }

    protected long outboxCount(String topic) {
        Long count =
                jdbc.sql("SELECT count(*) FROM auth.outbox WHERE topic = :topic")
                        .param("topic", topic)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    private String latestOutboxPayload(String topic) {
        return jdbc.sql(
                        """
                        SELECT payload FROM auth.outbox
                        WHERE topic = :topic
                        ORDER BY created_at DESC, id DESC
                        LIMIT 1
                        """)
                .param("topic", topic)
                .query(String.class)
                .optional()
                .orElseThrow(() -> new AssertionError("No outbox row for topic " + topic));
    }

    // --- flows used by several tests ------------------------------------------

    protected record Registered(String email, String password) {}

    /** Registers and verifies an account, returning credentials that can log in. */
    protected Registered registerAndVerify(String localPart) {
        String email = localPart + "-" + System.nanoTime() + "@pos.test";
        String password = "CorrectHorseBattery1";

        ResponseEntity<String> registered =
                post(
                        "/api/v1/auth/register",
                        Map.of(
                                "email", email,
                                "password", password,
                                "fullName", "Test Person"));
        if (registered.getStatusCode().value() != 202) {
            throw new AssertionError("Registration failed: " + registered.getBody());
        }

        String code = latestOtpFor(email);
        ResponseEntity<String> verified =
                post("/api/v1/auth/verify-otp", Map.of("email", email, "code", code));
        if (!verified.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Verification failed: " + verified.getBody());
        }

        return new Registered(email, password);
    }

    protected String loginForAccessToken(String email, String password) {
        ResponseEntity<String> response =
                post("/api/v1/auth/login", Map.of("email", email, "password", password));
        if (!response.getStatusCode().is2xxSuccessful()) {
            throw new AssertionError("Login failed: " + response.getBody());
        }
        return EventJson.mapper().readTree(response.getBody()).get("accessToken").asString();
    }

    /** The bootstrap SUPER_ADMIN created at startup, for tests that need full permissions. */
    /** The token version auth-service has published for a user, as the gateway would read it. */
    protected String publishedTokenVersion(java.util.UUID userId) {
        return redis.opsForValue().get(com.pos.common.security.TokenVersionKeys.forUser(userId));
    }

    protected String adminAccessToken() {
        return loginForAccessToken("bootstrap-admin@pos.test", "BootstrapAdminPassword1");
    }
}
