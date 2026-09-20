package com.pos.messaging.outbox;

import java.util.UUID;

import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.transaction.support.TransactionSynchronizationManager;

import com.pos.common.id.UuidV7;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;

/**
 * Records an event for publication, in the same database transaction as the state change that
 * caused it.
 *
 * <p>This is the whole point of the outbox. Publishing to Kafka directly from business logic has
 * two failure modes that both lose money: the broker is unreachable and the event vanishes while
 * the sale is committed, or the send succeeds and the transaction then rolls back, announcing a
 * sale that never happened. Writing a row in the same transaction makes the state change and the
 * intent to publish atomic - they commit together or not at all.
 *
 * <p>Call this from inside a {@code @Transactional} service method. Never call {@code
 * kafkaTemplate.send()} from business logic.
 */
public class OutboxRecorder {

    private final JdbcClient jdbc;

    public OutboxRecorder(JdbcClient jdbc) {
        this.jdbc = jdbc;
    }

    /**
     * @param topic the destination topic, from {@link com.pos.events.Topics}
     * @param aggregateType the kind of thing that changed, e.g. {@code Sale}
     * @param aggregateId the id of that thing; also the Kafka message key, so events for one
     *     aggregate stay ordered
     * @param envelope the event to publish
     */
    public void record(
            String topic, String aggregateType, UUID aggregateId, EventEnvelope<?> envelope) {

        // Without an active transaction the guarantee above silently does not hold, so this is a
        // hard failure rather than a warning.
        if (!TransactionSynchronizationManager.isActualTransactionActive()) {
            throw new IllegalStateException(
                    "OutboxRecorder.record() was called outside a transaction. The outbox row must"
                            + " commit atomically with the state change it describes - annotate the"
                            + " calling service method @Transactional.");
        }

        jdbc.sql(
                        """
                        INSERT INTO outbox (
                            id, event_id, aggregate_type, aggregate_id,
                            topic, event_type, payload, status, attempts,
                            created_at, next_attempt_at
                        ) VALUES (
                            :id, :eventId, :aggregateType, :aggregateId,
                            :topic, :eventType, :payload, 'PENDING', 0,
                            now(), now()
                        )
                        """)
                .param("id", UuidV7.randomUUID())
                .param("eventId", envelope.eventId())
                .param("aggregateType", aggregateType)
                .param("aggregateId", aggregateId)
                .param("topic", topic)
                .param("eventType", envelope.eventType())
                .param("payload", EventJson.write(envelope))
                .update();
    }
}
