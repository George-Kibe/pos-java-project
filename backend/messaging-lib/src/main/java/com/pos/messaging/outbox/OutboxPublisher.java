package com.pos.messaging.outbox;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.transaction.support.TransactionTemplate;

/**
 * Relays committed outbox rows to Kafka.
 *
 * <p>Claims a batch with {@code FOR UPDATE SKIP LOCKED}, so several instances of the same service
 * can run this concurrently without publishing the same row twice and without blocking each other.
 *
 * <p>Delivery is at-least-once, never exactly-once: a row can be sent successfully and the process
 * die before the row is marked published, and the next pass will send it again. That is why every
 * consumer must be idempotent - see {@link com.pos.messaging.idempotency.IdempotentConsumer}.
 */
public class OutboxPublisher {

    private static final Logger log = LoggerFactory.getLogger(OutboxPublisher.class);

    private final JdbcClient jdbc;
    private final KafkaTemplate<String, String> kafkaTemplate;
    private final TransactionTemplate transactionTemplate;
    private final OutboxProperties properties;

    public OutboxPublisher(
            JdbcClient jdbc,
            KafkaTemplate<String, String> kafkaTemplate,
            TransactionTemplate transactionTemplate,
            OutboxProperties properties) {
        this.jdbc = jdbc;
        this.kafkaTemplate = kafkaTemplate;
        this.transactionTemplate = transactionTemplate;
        this.properties = properties;
    }

    /** Publishes one batch of due rows. Returns how many were published successfully. */
    public int publishDue() {
        Integer published = transactionTemplate.execute(status -> publishBatch());
        return published == null ? 0 : published;
    }

    private int publishBatch() {
        List<PendingRow> batch =
                jdbc.sql(
                                """
                                SELECT id, topic, aggregate_id, payload, attempts, event_type
                                FROM outbox
                                WHERE status = 'PENDING' AND next_attempt_at <= now()
                                ORDER BY created_at
                                LIMIT :limit
                                FOR UPDATE SKIP LOCKED
                                """)
                        .param("limit", properties.getBatchSize())
                        .query(
                                (rs, rowNum) ->
                                        new PendingRow(
                                                rs.getObject("id", UUID.class),
                                                rs.getString("topic"),
                                                rs.getObject("aggregate_id", UUID.class),
                                                rs.getString("payload"),
                                                rs.getInt("attempts"),
                                                rs.getString("event_type")))
                        .list();

        int published = 0;
        for (PendingRow row : batch) {
            try {
                kafkaTemplate
                        .send(row.topic(), row.aggregateId().toString(), row.payload())
                        .get(properties.getSendTimeout().toMillis(), TimeUnit.MILLISECONDS);
                markPublished(row.id());
                published++;
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                markFailed(row, "interrupted while publishing");
                break;
            } catch (Exception e) {
                log.warn(
                        "Outbox publish failed for {} to {} (attempt {}): {}",
                        row.id(),
                        row.topic(),
                        row.attempts() + 1,
                        e.toString());
                markFailed(row, e.toString());
            }
        }
        if (published > 0) {
            log.debug("Published {} outbox rows", published);
        }
        return published;
    }

    private void markPublished(UUID id) {
        jdbc.sql(
                        "UPDATE outbox SET status = 'PUBLISHED', published_at = now(),"
                                + " last_error = NULL WHERE id = :id")
                .param("id", id)
                .update();
    }

    private void markFailed(PendingRow row, String error) {
        int attempts = row.attempts() + 1;
        if (attempts >= properties.getMaxAttempts()) {
            // Parked, not dropped. This is alertable: something is wrong that retrying will not
            // fix.
            log.error(
                    "Outbox row {} ({} -> {}) permanently failed after {} attempts: {}",
                    row.id(),
                    row.eventType(),
                    row.topic(),
                    attempts,
                    error);
            jdbc.sql(
                            "UPDATE outbox SET status = 'FAILED', attempts = :attempts,"
                                    + " last_error = :error WHERE id = :id")
                    .param("attempts", attempts)
                    .param("error", truncate(error))
                    .param("id", row.id())
                    .update();
            return;
        }

        Instant nextAttempt = Instant.now().plus(backoff(attempts));
        jdbc.sql(
                        "UPDATE outbox SET attempts = :attempts, last_error = :error,"
                                + " next_attempt_at = :nextAttempt WHERE id = :id")
                .param("attempts", attempts)
                .param("error", truncate(error))
                .param("nextAttempt", java.sql.Timestamp.from(nextAttempt))
                .param("id", row.id())
                .update();
    }

    /** Exponential, capped: 2s, 4s, 8s ... up to the configured ceiling. */
    private Duration backoff(int attempts) {
        long seconds = 1L << Math.min(attempts, 20);
        Duration candidate = Duration.ofSeconds(seconds);
        return candidate.compareTo(properties.getMaxBackoff()) > 0
                ? properties.getMaxBackoff()
                : candidate;
    }

    private static String truncate(String error) {
        return error != null && error.length() > 2000 ? error.substring(0, 2000) : error;
    }

    private record PendingRow(
            UUID id,
            String topic,
            UUID aggregateId,
            String payload,
            int attempts,
            String eventType) {}
}
