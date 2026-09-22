package com.pos.messaging.idempotency;

import java.io.ByteArrayInputStream;
import java.io.IOException;
import java.nio.charset.StandardCharsets;
import java.security.MessageDigest;
import java.security.NoSuchAlgorithmException;
import java.time.Duration;
import java.time.Instant;
import java.util.HexFormat;
import java.util.List;
import java.util.Optional;
import java.util.Set;

import jakarta.servlet.FilterChain;
import jakarta.servlet.ReadListener;
import jakarta.servlet.ServletException;
import jakarta.servlet.ServletInputStream;
import jakarta.servlet.http.HttpServletRequest;
import jakarta.servlet.http.HttpServletRequestWrapper;
import jakarta.servlet.http.HttpServletResponse;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.web.filter.OncePerRequestFilter;
import org.springframework.web.util.ContentCachingResponseWrapper;

import com.pos.common.correlation.CorrelationId;
import com.pos.common.id.UuidV7;

/**
 * Honours {@code Idempotency-Key} on every mutating request.
 *
 * <p>A lane that loses its connection mid-request cannot know whether the server acted, so it
 * retries with the same key. The first request's successful answer is stored and replayed to every
 * retry; without that, the retry adds the tin twice or takes the payment twice.
 *
 * <p>Three rules:
 *
 * <ul>
 *   <li>Only a <b>2xx</b> answer is stored. A 503 because catalog was down must stay retryable, and
 *       a validation error costs nothing to recompute.
 *   <li>The same key with a <b>different request</b> is refused with 422. It is a client bug, and
 *       answering it with the first request's response would tell the client something false.
 *   <li>A retry that arrives while the first is still running gets 409, not a second execution.
 * </ul>
 *
 * <p>The record is written in autocommit, outside the business transaction, because it has to exist
 * before the work starts and survive the work failing. The residual risk: a process that dies after
 * the business commit and before the record is marked complete leaves it IN_PROGRESS; after {@link
 * #ABANDONED_AFTER} a retry takes it over and executes again. For checkout and tender that second
 * execution is refused by the state machine; for adding a line it would add twice. That window is a
 * crash between two statements, and the alternative - holding the business transaction open across
 * the response write - is worse.
 *
 * <p>Shared by every service a terminal calls, and switched on with {@code
 * pos.idempotency.enabled=true}. Each such service creates the {@code idempotency_records} table in
 * its own migration: a shared migration added after services are past V1 would fail their Flyway
 * validation as a skipped version.
 *
 * <p>Keys are scoped to {@link HttpServletRequest#getUserPrincipal()}, which Spring Security fills
 * with the verified token's subject, so this needs no security dependency of its own.
 */
public class IdempotencyFilter extends OncePerRequestFilter {

    private static final Logger log = LoggerFactory.getLogger(IdempotencyFilter.class);

    public static final String HEADER = "Idempotency-Key";
    public static final String REPLAYED_HEADER = "Idempotent-Replayed";

    static final Duration ABANDONED_AFTER = Duration.ofSeconds(60);
    private static final Set<String> MUTATING = Set.of("POST", "PUT", "PATCH", "DELETE");
    private static final int MAX_KEY_LENGTH = 120;

    private final JdbcClient jdbc;
    private final List<String> excludedPaths;

    /**
     * @param excludedPaths path prefixes that keep their own idempotency, such as a batch endpoint
     *     that stores a per-item answer under the same key
     */
    public IdempotencyFilter(JdbcClient jdbc, List<String> excludedPaths) {
        this.jdbc = jdbc;
        this.excludedPaths = List.copyOf(excludedPaths);
    }

    @Override
    protected boolean shouldNotFilter(HttpServletRequest request) {
        return !MUTATING.contains(request.getMethod())
                || request.getHeader(HEADER) == null
                || !request.getRequestURI().startsWith("/api/v1/")
                || excludedPaths.stream().anyMatch(request.getRequestURI()::startsWith);
    }

    @Override
    protected void doFilterInternal(
            HttpServletRequest request, HttpServletResponse response, FilterChain chain)
            throws ServletException, IOException {

        String key = request.getHeader(HEADER).trim();
        if (key.isEmpty() || key.length() > MAX_KEY_LENGTH) {
            writeProblem(
                    response,
                    400,
                    "request.invalid_idempotency_key",
                    "Idempotency-Key must be 1 to %d characters".formatted(MAX_KEY_LENGTH));
            return;
        }

        byte[] body = request.getInputStream().readAllBytes();
        String principal = principal(request);
        String hash = hash(request.getMethod(), request.getRequestURI(), body);

        if (!claim(key, principal, hash)) {
            Optional<StoredRecord> existing = find(key, principal);
            if (existing.isEmpty()) {
                // Deleted between our failed insert and this read: a failed first attempt was
                // cleared. Treat this request as the first.
                if (!claim(key, principal, hash)) {
                    writeProblem(response, 409, "request.in_progress", "Request is being retried");
                    return;
                }
            } else {
                StoredRecord record = existing.get();
                if (!record.requestHash().equals(hash)) {
                    writeProblem(
                            response,
                            422,
                            "request.idempotency_key_reused",
                            "This Idempotency-Key was already used for a different request");
                    return;
                }
                if ("COMPLETED".equals(record.status())) {
                    replay(response, record);
                    return;
                }
                if (record.createdAt().isAfter(Instant.now().minus(ABANDONED_AFTER))) {
                    writeProblem(
                            response,
                            409,
                            "request.in_progress",
                            "The first attempt with this Idempotency-Key is still being processed");
                    return;
                }
                log.warn("Taking over abandoned idempotent request {} for {}", key, principal);
                release(key, principal);
                if (!claim(key, principal, hash)) {
                    writeProblem(response, 409, "request.in_progress", "Request is being retried");
                    return;
                }
            }
        }

        ContentCachingResponseWrapper wrapped = new ContentCachingResponseWrapper(response);
        boolean stored = false;
        try {
            chain.doFilter(new CachedBodyRequest(request, body), wrapped);

            int status = wrapped.getStatus();
            if (status >= 200 && status < 300) {
                complete(
                        key,
                        principal,
                        status,
                        new String(wrapped.getContentAsByteArray(), StandardCharsets.UTF_8),
                        wrapped.getContentType());
                stored = true;
            }
        } finally {
            if (!stored) {
                // Failed or refused: let a retry try again rather than replaying a failure.
                release(key, principal);
            }
            wrapped.copyBodyToResponse();
        }
    }

    // --- storage --------------------------------------------------------------------------

    private boolean claim(String key, String principal, String hash) {
        return jdbc.sql(
                                """
                                INSERT INTO idempotency_records
                                    (id, idempotency_key, principal, request_hash, status)
                                VALUES (:id, :key, :principal, :hash, 'IN_PROGRESS')
                                ON CONFLICT (principal, idempotency_key) DO NOTHING
                                """)
                        .param("id", UuidV7.randomUUID())
                        .param("key", key)
                        .param("principal", principal)
                        .param("hash", hash)
                        .update()
                == 1;
    }

    private Optional<StoredRecord> find(String key, String principal) {
        return jdbc.sql(
                        """
                        SELECT request_hash, status, response_status, response_body, content_type,
                               created_at
                        FROM idempotency_records
                        WHERE principal = :principal AND idempotency_key = :key
                        """)
                .param("key", key)
                .param("principal", principal)
                .query(
                        (rs, row) ->
                                new StoredRecord(
                                        rs.getString("request_hash"),
                                        rs.getString("status"),
                                        rs.getInt("response_status"),
                                        rs.getString("response_body"),
                                        rs.getString("content_type"),
                                        rs.getTimestamp("created_at").toInstant()))
                .optional();
    }

    private void complete(
            String key, String principal, int status, String body, String contentType) {
        jdbc.sql(
                        """
                        UPDATE idempotency_records
                        SET status = 'COMPLETED', response_status = :status,
                            response_body = :body, content_type = :contentType,
                            completed_at = now()
                        WHERE principal = :principal AND idempotency_key = :key
                        """)
                .param("status", status)
                .param("body", body)
                .param("contentType", contentType)
                .param("key", key)
                .param("principal", principal)
                .update();
    }

    private void release(String key, String principal) {
        jdbc.sql(
                        """
                        DELETE FROM idempotency_records
                        WHERE principal = :principal AND idempotency_key = :key
                          AND status = 'IN_PROGRESS'
                        """)
                .param("key", key)
                .param("principal", principal)
                .update();
    }

    // --- responses ------------------------------------------------------------------------

    private static void replay(HttpServletResponse response, StoredRecord record)
            throws IOException {
        response.setStatus(record.responseStatus());
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        if (record.contentType() != null) {
            response.setContentType(record.contentType());
        }
        response.setHeader(REPLAYED_HEADER, "true");
        if (record.responseBody() != null) {
            response.getWriter().write(record.responseBody());
        }
    }

    /**
     * Hand-written problem+json, so the charset is set explicitly: the servlet default mangles it.
     */
    private static void writeProblem(
            HttpServletResponse response, int status, String code, String detail)
            throws IOException {
        response.setStatus(status);
        response.setCharacterEncoding(StandardCharsets.UTF_8.name());
        response.setContentType(MediaType.APPLICATION_PROBLEM_JSON_VALUE);
        String correlationId = CorrelationId.get();
        response.getWriter()
                .write(
                        """
                        {"type":"https://docs.pos.local/problems/%s","title":"%s","status":%d,\
                        "detail":"%s","code":"%s","correlationId":%s}"""
                                .formatted(
                                        code,
                                        org.springframework.http.HttpStatus.valueOf(status)
                                                .getReasonPhrase(),
                                        status,
                                        detail.replace("\"", "'"),
                                        code,
                                        correlationId == null
                                                ? "null"
                                                : "\"" + correlationId + "\""));
    }

    // --- helpers --------------------------------------------------------------------------

    private static String principal(HttpServletRequest request) {
        return request.getUserPrincipal() == null
                ? "anonymous"
                : request.getUserPrincipal().getName();
    }

    private static String hash(String method, String uri, byte[] body) {
        try {
            MessageDigest digest = MessageDigest.getInstance("SHA-256");
            digest.update(method.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(uri.getBytes(StandardCharsets.UTF_8));
            digest.update((byte) 0);
            digest.update(body);
            return HexFormat.of().formatHex(digest.digest());
        } catch (NoSuchAlgorithmException e) {
            throw new IllegalStateException("SHA-256 is always available", e);
        }
    }

    private record StoredRecord(
            String requestHash,
            String status,
            int responseStatus,
            String responseBody,
            String contentType,
            Instant createdAt) {}

    /** Replays a body that the filter has already read, so the controller can read it again. */
    private static final class CachedBodyRequest extends HttpServletRequestWrapper {
        private final byte[] body;

        CachedBodyRequest(HttpServletRequest request, byte[] body) {
            super(request);
            this.body = body;
        }

        @Override
        public ServletInputStream getInputStream() {
            ByteArrayInputStream source = new ByteArrayInputStream(body);
            return new ServletInputStream() {
                @Override
                public boolean isFinished() {
                    return source.available() == 0;
                }

                @Override
                public boolean isReady() {
                    return true;
                }

                @Override
                public void setReadListener(ReadListener listener) {
                    throw new UnsupportedOperationException("Synchronous reads only");
                }

                @Override
                public int read() {
                    return source.read();
                }
            };
        }

        @Override
        public java.io.BufferedReader getReader() {
            return new java.io.BufferedReader(
                    new java.io.InputStreamReader(getInputStream(), StandardCharsets.UTF_8));
        }
    }
}
