package com.pos.reporting.service;

import java.sql.Timestamp;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.events.EventJson;

import lombok.RequiredArgsConstructor;
import tools.jackson.databind.JsonNode;

/**
 * Takes one event in: logs it, then projects it, in one transaction.
 *
 * <p>The log insert is the idempotency check. {@code event_id} is unique, so a redelivered event
 * inserts nothing and is not projected again - the same guarantee {@code processed_event} gives the
 * other services, with the event kept as well, because a rebuild needs it.
 *
 * <p>Holds the rebuild lock in shared mode: any number of events may be taken in at once, but none
 * while a rebuild is replacing the facts underneath them.
 */
@Service
@RequiredArgsConstructor
public class EventIngestor {

    private static final Logger log = LoggerFactory.getLogger(EventIngestor.class);

    /** Shared by ingestion and rebuild; any constant both agree on. */
    static final long REBUILD_LOCK = 0x5245504f52544c4bL;

    private final JdbcClient jdbc;
    private final Projector projector;

    /**
     * @return false when the event had been taken in before
     */
    @Transactional
    public boolean ingest(String topic, String json) {
        jdbc.sql("SELECT pg_advisory_xact_lock_shared(:lock)")
                .param("lock", REBUILD_LOCK)
                .query()
                .singleRow();

        JsonNode envelope = EventJson.mapper().readTree(json);
        String eventId = text(envelope, "eventId");
        if (eventId == null) {
            // Not an envelope: nothing to key it on, so nothing to log or project. Loud, because a
            // producer is sending something the contract does not allow.
            log.error("Event on {} has no eventId; ignored", topic);
            return false;
        }
        String occurredAt = text(envelope, "occurredAt");

        int inserted =
                jdbc.sql(
                                """
                                INSERT INTO event_log
                                    (event_id, topic, event_type, occurred_at, payload)
                                VALUES (:id, :topic, :type, :at, :payload)
                                ON CONFLICT (event_id) DO NOTHING
                                """)
                        .param("id", eventId)
                        .param("topic", topic)
                        .param("type", text(envelope, "eventType"))
                        .param(
                                "at",
                                occurredAt == null
                                        ? null
                                        : Timestamp.from(java.time.Instant.parse(occurredAt)))
                        .param("payload", json)
                        .update();
        if (inserted == 0) {
            log.debug("Event {} already taken in", eventId);
            return false;
        }
        projector.apply(topic, json);
        return true;
    }

    private static String text(JsonNode node, String field) {
        JsonNode value = node.get(field);
        return value == null || value.isNull() ? null : value.asString();
    }
}
