package com.pos.events;

import java.time.Instant;
import java.util.Objects;
import java.util.UUID;

import com.fasterxml.jackson.annotation.JsonIgnoreProperties;

/**
 * The envelope every Kafka message in the system is wrapped in.
 *
 * <p>{@code correlationId} originates at the gateway and threads through every HTTP hop and every
 * event, so one identifier follows a sale from the scan at the lane to the receipt email. {@code
 * causationId} is the {@code eventId} of the event that caused this one, which makes a chain of
 * events reconstructable after the fact.
 *
 * <p>Unknown properties are ignored on read: a consumer running an older build must tolerate fields
 * added by a newer producer. That is what makes additive schema evolution safe.
 *
 * @param <T> the payload type, one of the records in this library
 */
@JsonIgnoreProperties(ignoreUnknown = true)
public record EventEnvelope<T>(
        String eventId,
        String eventType,
        int schemaVersion,
        Instant occurredAt,
        String correlationId,
        String causationId,
        UUID branchId,
        UUID actorId,
        T payload) {

    public EventEnvelope {
        Objects.requireNonNull(eventId, "eventId");
        Objects.requireNonNull(eventType, "eventType");
        Objects.requireNonNull(occurredAt, "occurredAt");
        Objects.requireNonNull(payload, "payload");
        if (eventId.isBlank()) {
            throw new IllegalArgumentException("eventId must not be blank");
        }
        if (eventType.isBlank()) {
            throw new IllegalArgumentException("eventType must not be blank");
        }
        if (schemaVersion < 1) {
            throw new IllegalArgumentException("schemaVersion must be >= 1, was " + schemaVersion);
        }
    }

    public static <T> Builder<T> builder() {
        return new Builder<>();
    }

    /**
     * Mutable builder. {@code eventId} defaults to a fresh UUID and {@code occurredAt} to now, so
     * the common case needs only the event type and payload.
     */
    public static final class Builder<T> {
        private String eventId = UUID.randomUUID().toString();
        private String eventType;
        private int schemaVersion = 1;
        private Instant occurredAt = Instant.now();
        private String correlationId;
        private String causationId;
        private UUID branchId;
        private UUID actorId;
        private T payload;

        private Builder() {}

        public Builder<T> eventId(String v) {
            this.eventId = v;
            return this;
        }

        /** Sets the event type from a topic name, e.g. {@code pos.auth.otp-requested.v1}. */
        public Builder<T> topic(String topic) {
            this.eventType = Topics.eventTypeOf(topic);
            this.schemaVersion = Topics.schemaVersionOf(topic);
            return this;
        }

        public Builder<T> eventType(String v) {
            this.eventType = v;
            return this;
        }

        public Builder<T> schemaVersion(int v) {
            this.schemaVersion = v;
            return this;
        }

        public Builder<T> occurredAt(Instant v) {
            this.occurredAt = v;
            return this;
        }

        public Builder<T> correlationId(String v) {
            this.correlationId = v;
            return this;
        }

        public Builder<T> causationId(String v) {
            this.causationId = v;
            return this;
        }

        public Builder<T> branchId(UUID v) {
            this.branchId = v;
            return this;
        }

        public Builder<T> actorId(UUID v) {
            this.actorId = v;
            return this;
        }

        public Builder<T> payload(T v) {
            this.payload = v;
            return this;
        }

        public EventEnvelope<T> build() {
            return new EventEnvelope<>(
                    eventId,
                    eventType,
                    schemaVersion,
                    occurredAt,
                    correlationId,
                    causationId,
                    branchId,
                    actorId,
                    payload);
        }
    }
}
