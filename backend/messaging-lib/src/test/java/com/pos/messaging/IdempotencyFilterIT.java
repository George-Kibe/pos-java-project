package com.pos.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.content;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.util.HexFormat;
import java.util.List;
import java.util.concurrent.atomic.AtomicInteger;

import org.junit.jupiter.api.BeforeAll;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.postgresql.ds.PGSimpleDataSource;
import org.springframework.http.HttpStatus;
import org.springframework.http.MediaType;
import org.springframework.http.ResponseEntity;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.MockHttpServletRequestBuilder;
import org.springframework.test.web.servlet.setup.MockMvcBuilders;
import org.springframework.web.bind.annotation.PostMapping;
import org.springframework.web.bind.annotation.RequestBody;
import org.springframework.web.bind.annotation.RestController;

import com.pos.messaging.idempotency.IdempotencyFilter;

/**
 * What a terminal retrying a request gets back, against a real PostgreSQL.
 *
 * <p>The controller counts its own executions, so "replayed" is proved by the handler not having
 * run again rather than by the response merely looking the same.
 */
class IdempotencyFilterIT {

    private static JdbcClient jdbc;

    private final CountingController controller = new CountingController();
    private MockMvc mockMvc;

    @BeforeAll
    static void schema() {
        PGSimpleDataSource dataSource = new PGSimpleDataSource();
        dataSource.setUrl(MessagingContainers.POSTGRES.getJdbcUrl());
        dataSource.setUser(MessagingContainers.POSTGRES.getUsername());
        dataSource.setPassword(MessagingContainers.POSTGRES.getPassword());
        // Its own schema: the Flyway-managed ITs sharing this container expect public to be theirs.
        JdbcClient.create(dataSource).sql("CREATE SCHEMA IF NOT EXISTS idempotency_it").update();
        dataSource.setCurrentSchema("idempotency_it");
        jdbc = JdbcClient.create(dataSource);
        // The DDL each service using the filter carries in its own migration.
        jdbc.sql(
                        """
                        CREATE TABLE IF NOT EXISTS idempotency_records (
                            id               UUID PRIMARY KEY,
                            idempotency_key  VARCHAR(120)  NOT NULL,
                            principal        VARCHAR(64)   NOT NULL,
                            request_hash     VARCHAR(64)   NOT NULL,
                            status           VARCHAR(20)   NOT NULL,
                            response_status  INTEGER,
                            response_body    TEXT,
                            content_type     VARCHAR(100),
                            created_at       TIMESTAMPTZ   NOT NULL DEFAULT now(),
                            completed_at     TIMESTAMPTZ,
                            CONSTRAINT uq_idempotency_key UNIQUE (principal, idempotency_key)
                        )
                        """)
                .update();
    }

    @BeforeEach
    void setUp() {
        jdbc.sql("DELETE FROM idempotency_records").update();
        mockMvc =
                MockMvcBuilders.standaloneSetup(controller)
                        .addFilters(new IdempotencyFilter(jdbc, List.of("/api/v1/batches")))
                        .build();
    }

    @Test
    @DisplayName("a retry with the same key gets the first answer and does not run again")
    void aRetryIsReplayed() throws Exception {
        mockMvc.perform(request("/api/v1/things", "k-1", "{\"n\":1}"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.execution").value(1));

        mockMvc.perform(request("/api/v1/things", "k-1", "{\"n\":1}"))
                .andExpect(status().isCreated())
                .andExpect(header().string(IdempotencyFilter.REPLAYED_HEADER, "true"))
                .andExpect(jsonPath("$.execution").value(1));

        assertThat(controller.executions.get()).isEqualTo(1);
    }

    @Test
    void theSameKeyForADifferentRequestIsRefused() throws Exception {
        mockMvc.perform(request("/api/v1/things", "k-2", "{\"n\":1}"))
                .andExpect(status().isCreated());

        mockMvc.perform(request("/api/v1/things", "k-2", "{\"n\":2}"))
                .andExpect(status().isUnprocessableContent())
                .andExpect(content().contentTypeCompatibleWith(MediaType.APPLICATION_PROBLEM_JSON))
                .andExpect(jsonPath("$.code").value("request.idempotency_key_reused"));
        assertThat(controller.executions.get()).isEqualTo(1);
    }

    @Test
    @DisplayName("a failure is not stored, so the retry really retries")
    void aFailureStaysRetryable() throws Exception {
        mockMvc.perform(request("/api/v1/things", "k-3", "{\"fail\":true}"))
                .andExpect(status().isServiceUnavailable());
        mockMvc.perform(request("/api/v1/things", "k-3", "{\"fail\":true}"))
                .andExpect(status().isServiceUnavailable());

        assertThat(controller.executions.get()).isEqualTo(2);
        assertThat(rows()).isZero();
    }

    @Test
    void keysAreScopedPerCaller() throws Exception {
        mockMvc.perform(request("/api/v1/things", "shared", "{}").principal(() -> "till-7"))
                .andExpect(status().isCreated());
        mockMvc.perform(request("/api/v1/things", "shared", "{}").principal(() -> "till-8"))
                .andExpect(status().isCreated())
                .andExpect(jsonPath("$.execution").value(2));
    }

    @Test
    @DisplayName("a retry while the first attempt is still running is told so, not executed")
    void aConcurrentRetryIsAConflict() throws Exception {
        claimInProgress("k-4", "now()");

        mockMvc.perform(request("/api/v1/things", "k-4", "{}"))
                .andExpect(status().isConflict())
                .andExpect(jsonPath("$.code").value("request.in_progress"));
        assertThat(controller.executions.get()).isZero();
    }

    @Test
    @DisplayName("an attempt abandoned by a crashed process is taken over")
    void anAbandonedAttemptIsTakenOver() throws Exception {
        claimInProgress("k-5", "now() - interval '5 minutes'");

        mockMvc.perform(request("/api/v1/things", "k-5", "{}")).andExpect(status().isCreated());
        assertThat(controller.executions.get()).isEqualTo(1);
    }

    @Test
    void requestsWithoutAKeyOrOnExcludedPathsPassStraightThrough() throws Exception {
        mockMvc.perform(
                        post("/api/v1/things")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{}"))
                .andExpect(status().isCreated());
        mockMvc.perform(request("/api/v1/batches", "k-6", "{}")).andExpect(status().isOk());
        mockMvc.perform(request("/api/v1/batches", "k-6", "{}")).andExpect(status().isOk());

        assertThat(controller.executions.get()).isEqualTo(3);
        assertThat(rows()).isZero();
    }

    @Test
    void anOversizedKeyIsABadRequest() throws Exception {
        mockMvc.perform(request("/api/v1/things", "x".repeat(121), "{}"))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code").value("request.invalid_idempotency_key"));
    }

    // --- helpers ----------------------------------------------------------------

    private static MockHttpServletRequestBuilder request(String path, String key, String body) {
        return post(path)
                .header(IdempotencyFilter.HEADER, key)
                .principal(() -> "till-1")
                .contentType(MediaType.APPLICATION_JSON)
                .content(body);
    }

    /** Seeds a first attempt for the same request, so only its status decides the answer. */
    private static void claimInProgress(String key, String createdAt) throws Exception {
        jdbc.sql(
                        """
                        INSERT INTO idempotency_records
                            (id, idempotency_key, principal, request_hash, status, created_at)
                        VALUES (gen_random_uuid(), :key, 'till-1', :hash, 'IN_PROGRESS', %s)
                        """
                                .formatted(createdAt))
                .param("key", key)
                .param("hash", hash("POST", "/api/v1/things", "{}"))
                .update();
    }

    /** The filter's request fingerprint: method, path and body, NUL-separated. */
    private static String hash(String method, String uri, String body) throws Exception {
        MessageDigest digest = MessageDigest.getInstance("SHA-256");
        digest.update(method.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(uri.getBytes(StandardCharsets.UTF_8));
        digest.update((byte) 0);
        digest.update(body.getBytes(StandardCharsets.UTF_8));
        return HexFormat.of().formatHex(digest.digest());
    }

    private static long rows() {
        Long count =
                jdbc.sql("SELECT count(*) FROM idempotency_records").query(Long.class).single();
        return count == null ? 0 : count;
    }

    @RestController
    static class CountingController {

        final AtomicInteger executions = new AtomicInteger();

        @PostMapping("/api/v1/things")
        ResponseEntity<String> create(@RequestBody String body) {
            int execution = executions.incrementAndGet();
            if (body.contains("fail")) {
                return ResponseEntity.status(HttpStatus.SERVICE_UNAVAILABLE).build();
            }
            return ResponseEntity.status(HttpStatus.CREATED)
                    .contentType(MediaType.APPLICATION_JSON)
                    .body("{\"execution\":" + execution + "}");
        }

        @PostMapping("/api/v1/batches")
        String batch(@RequestBody String body) {
            executions.incrementAndGet();
            return "ok";
        }
    }
}
