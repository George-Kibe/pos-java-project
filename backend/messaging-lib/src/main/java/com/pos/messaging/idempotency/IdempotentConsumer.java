package com.pos.messaging.idempotency;

import java.util.function.Consumer;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pos.events.EventEnvelope;

/**
 * Runs an event handler at most once per (event, consumer) pair.
 *
 * <p>Kafka guarantees at-least-once delivery, so a consumer will see the same event again after a
 * rebalance, a retry or a redeployment part-way through a batch. In this domain a second delivery
 * is not harmless: it would deduct stock twice, accrue loyalty points twice, or send a second
 * receipt. Every handler therefore goes through here.
 *
 * <p>The marker row is inserted <em>before</em> the handler runs, inside the same transaction. If
 * the handler throws, the transaction rolls back the marker along with everything else, so the
 * redelivery is retried rather than silently skipped. If it succeeds, both commit together.
 */
public class IdempotentConsumer {

    private static final Logger log = LoggerFactory.getLogger(IdempotentConsumer.class);

    private final JdbcClient jdbc;

    public IdempotentConsumer(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * Runs {@code handler} unless this consumer has already processed this event.
     *
     * @param consumer a stable name for the handler, e.g. {@code inventory.deduct-on-sale}. Two
     *     handlers in one service legitimately process the same event, so the name is part of the
     *     key.
     * @return true if the handler ran, false if this was a duplicate delivery
     */
    public <T> boolean consumeOnce(
            EventEnvelope<T> envelope, String consumer, Consumer<EventEnvelope<T>> handler) {

        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "IdempotentConsumer.consumeOnce() was called outside a transaction. The marker"
                            + " row and the handler's own writes must commit together, otherwise a"
                            + " failed handler would be recorded as processed - annotate the listener"
                            + " @Transactional.");
        }

        int inserted =
                jdbc.sql(
                                """
                                INSERT INTO processed_event (event_id, consumer, event_type)
                                VALUES (:eventId, :consumer, :eventType)
                                ON CONFLICT (event_id, consumer) DO NOTHING
                                """)
                        .param("eventId", envelope.eventId())
                        .param("consumer", consumer)
                        .param("eventType", envelope.eventType())
                        .update();

        if (inserted == 0) {
            log.debug(
                    "Skipping duplicate delivery of {} ({}) to {}",
                    envelope.eventId(),
                    envelope.eventType(),
                    consumer);
            return false;
        }

        handler.accept(envelope);
        return true;
    }

    /** Whether this consumer has already handled the event. Intended for tests and diagnostics. */
    public boolean hasProcessed(String eventId, String consumer) {
        Integer count =
                jdbc.sql(
                                "SELECT count(*) FROM processed_event"
                                        + " WHERE event_id = :eventId AND consumer = :consumer")
                        .param("eventId", eventId)
                        .param("consumer", consumer)
                        .query(Integer.class)
                        .single();
        return count != null && count > 0;
    }
}
