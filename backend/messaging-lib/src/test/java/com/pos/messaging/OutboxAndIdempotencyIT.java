package com.pos.messaging;

import static org.assertj.core.api.Assertions.assertThat;
import static org.assertj.core.api.Assertions.assertThatThrownBy;

import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Properties;
import java.util.UUID;
import java.util.concurrent.CompletableFuture;
import java.util.concurrent.atomic.AtomicInteger;

import org.apache.kafka.clients.consumer.ConsumerConfig;
import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.apache.kafka.clients.consumer.ConsumerRecords;
import org.apache.kafka.clients.consumer.KafkaConsumer;
import org.apache.kafka.common.serialization.StringDeserializer;
import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.mockito.Mockito;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.auth.OtpPurpose;
import com.pos.events.auth.OtpRequestedPayload;
import com.pos.messaging.idempotency.IdempotentConsumer;
import com.pos.messaging.outbox.OutboxProperties;
import com.pos.messaging.outbox.OutboxPublisher;
import com.pos.messaging.outbox.OutboxRecorder;

/**
 * Proves the two guarantees the whole event-driven architecture rests on, against a real PostgreSQL
 * and a real Kafka broker: a recorded event reaches the topic, and a redelivered event is handled
 * exactly once.
 */
@SpringBootTest(classes = MessagingTestApplication.class)
class OutboxAndIdempotencyIT {

    @DynamicPropertySource
    static void properties(DynamicPropertyRegistry registry) {
        MessagingContainers.registerDataSource(registry);
        MessagingContainers.registerKafka(registry);
    }

    @Autowired private OutboxRecorder recorder;
    @Autowired private OutboxPublisher publisher;
    @Autowired private IdempotentConsumer idempotentConsumer;
    @Autowired private JdbcClient jdbc;
    @Autowired private PlatformTransactionManager transactionManager;

    private TransactionTemplate tx;

    @BeforeEach
    void setUp() {
        tx = new TransactionTemplate(transactionManager);
        jdbc.sql("TRUNCATE outbox, processed_event").update();
    }

    // --- the outbox -----------------------------------------------------------

    @Test
    @DisplayName("an event recorded in a transaction reaches Kafka once the relay runs")
    void recordedEventIsPublishedToKafka() {
        UUID userId = UUID.randomUUID();
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(userId);

        tx.executeWithoutResult(
                status -> recorder.record(Topics.AUTH_OTP_REQUESTED, "User", userId, envelope));

        // Committed but not yet relayed.
        assertThat(statusOf(envelope.eventId())).isEqualTo("PENDING");

        int published = publisher.publishDue();
        assertThat(published).isEqualTo(1);
        assertThat(statusOf(envelope.eventId())).isEqualTo("PUBLISHED");

        ConsumerRecord<String, String> record =
                consumeFor(Topics.AUTH_OTP_REQUESTED, userId.toString());
        assertThat(record).isNotNull();
        // Keyed by aggregate id, so every event for this user keeps its order.
        assertThat(record.key()).isEqualTo(userId.toString());

        EventEnvelope<OtpRequestedPayload> received =
                EventJson.readEnvelope(record.value(), OtpRequestedPayload.class);
        assertThat(received.eventId()).isEqualTo(envelope.eventId());
        assertThat(received.eventType()).isEqualTo("auth.otp-requested");
        assertThat(received.payload().email()).isEqualTo("ada@example.com");
    }

    @Test
    @DisplayName("a rolled-back transaction publishes nothing")
    void eventsFromRolledBackTransactionsAreNeverPublished() {
        UUID userId = UUID.randomUUID();
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(userId);

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status -> {
                                            recorder.record(
                                                    Topics.AUTH_OTP_REQUESTED,
                                                    "User",
                                                    userId,
                                                    envelope);
                                            throw new IllegalStateException("business rule failed");
                                        }))
                .isInstanceOf(IllegalStateException.class);

        // This is the guarantee: no state change, therefore no event.
        assertThat(countOutboxRows()).isZero();
        assertThat(publisher.publishDue()).isZero();
    }

    @Test
    @DisplayName(
            "recording outside a transaction fails loudly rather than silently losing the guarantee")
    void recordingOutsideATransactionIsRejected() {
        UUID userId = UUID.randomUUID();
        assertThatThrownBy(
                        () ->
                                recorder.record(
                                        Topics.AUTH_OTP_REQUESTED,
                                        "User",
                                        userId,
                                        otpEvent(userId)))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }

    @Test
    void publishingIsIdempotentAcrossRelayPasses() {
        UUID userId = UUID.randomUUID();
        tx.executeWithoutResult(
                status ->
                        recorder.record(
                                Topics.AUTH_USER_REGISTERED, "User", userId, otpEvent(userId)));

        assertThat(publisher.publishDue()).isEqualTo(1);
        // A second pass must find nothing left to do.
        assertThat(publisher.publishDue()).isZero();
    }

    // --- idempotent consumption ----------------------------------------------

    @Test
    @DisplayName("a redelivered event is handled exactly once")
    void duplicateDeliveryIsIgnored() {
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(UUID.randomUUID());
        AtomicInteger handled = new AtomicInteger();

        boolean first =
                tx.execute(
                        status ->
                                idempotentConsumer.consumeOnce(
                                        envelope, "test.handler", e -> handled.incrementAndGet()));
        boolean second =
                tx.execute(
                        status ->
                                idempotentConsumer.consumeOnce(
                                        envelope, "test.handler", e -> handled.incrementAndGet()));

        assertThat(first).isTrue();
        assertThat(second).isFalse();
        assertThat(handled).hasValue(1);
    }

    @Test
    @DisplayName("two different consumers each handle the same event once")
    void differentConsumersEachProcessTheEvent() {
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(UUID.randomUUID());
        AtomicInteger inventory = new AtomicInteger();
        AtomicInteger reporting = new AtomicInteger();

        tx.execute(
                s ->
                        idempotentConsumer.consumeOnce(
                                envelope, "inventory.deduct", e -> inventory.incrementAndGet()));
        tx.execute(
                s ->
                        idempotentConsumer.consumeOnce(
                                envelope, "reporting.project", e -> reporting.incrementAndGet()));

        assertThat(inventory).hasValue(1);
        assertThat(reporting).hasValue(1);
    }

    @Test
    @DisplayName("a handler that throws leaves no marker, so the redelivery is retried not skipped")
    void failedHandlerDoesNotMarkTheEventProcessed() {
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(UUID.randomUUID());

        assertThatThrownBy(
                        () ->
                                tx.executeWithoutResult(
                                        status ->
                                                idempotentConsumer.consumeOnce(
                                                        envelope,
                                                        "flaky.handler",
                                                        e -> {
                                                            throw new IllegalStateException("boom");
                                                        })))
                .isInstanceOf(IllegalStateException.class);

        // Had the marker survived the rollback, this event would be lost forever.
        assertThat(idempotentConsumer.hasProcessed(envelope.eventId(), "flaky.handler")).isFalse();

        AtomicInteger handled = new AtomicInteger();
        boolean retried =
                tx.execute(
                        status ->
                                idempotentConsumer.consumeOnce(
                                        envelope, "flaky.handler", e -> handled.incrementAndGet()));
        assertThat(retried).isTrue();
        assertThat(handled).hasValue(1);
    }

    @Test
    void consumingOutsideATransactionIsRejected() {
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(UUID.randomUUID());
        assertThatThrownBy(() -> idempotentConsumer.consumeOnce(envelope, "test.handler", e -> {}))
                .isInstanceOf(IllegalStateException.class)
                .hasMessageContaining("outside a transaction");
    }

    // --- helpers --------------------------------------------------------------

    private static EventEnvelope<OtpRequestedPayload> otpEvent(UUID userId) {
        return EventEnvelope.<OtpRequestedPayload>builder()
                .topic(Topics.AUTH_OTP_REQUESTED)
                .correlationId("it-correlation")
                .payload(
                        new OtpRequestedPayload(
                                userId,
                                "ada@example.com",
                                "Ada",
                                "123456",
                                OtpPurpose.REGISTRATION,
                                Instant.now().plusSeconds(600)))
                .build();
    }

    private String statusOf(String eventId) {
        return jdbc.sql("SELECT status FROM outbox WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private int countOutboxRows() {
        Integer count = jdbc.sql("SELECT count(*) FROM outbox").query(Integer.class).single();
        return count == null ? 0 : count;
    }

    /**
     * Reads from the beginning until the record with this key appears.
     *
     * <p>Not "the first record on the topic": the broker is shared with the other test class in
     * this module, so the first record is whichever test got there first. Selecting by key makes
     * the assertion about this test's own message.
     */
    private ConsumerRecord<String, String> consumeFor(String topic, String key) {
        Properties props = new Properties();
        props.put(
                ConsumerConfig.BOOTSTRAP_SERVERS_CONFIG,
                MessagingContainers.KAFKA.getBootstrapServers());
        props.put(ConsumerConfig.GROUP_ID_CONFIG, "it-" + UUID.randomUUID());
        props.put(ConsumerConfig.AUTO_OFFSET_RESET_CONFIG, "earliest");
        props.put(ConsumerConfig.KEY_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);
        props.put(ConsumerConfig.VALUE_DESERIALIZER_CLASS_CONFIG, StringDeserializer.class);

        try (KafkaConsumer<String, String> consumer = new KafkaConsumer<>(props)) {
            consumer.subscribe(List.of(topic));
            long deadline = System.currentTimeMillis() + 30_000;
            while (System.currentTimeMillis() < deadline) {
                ConsumerRecords<String, String> records = consumer.poll(Duration.ofMillis(500));
                for (ConsumerRecord<String, String> record : records) {
                    if (key.equals(record.key())) {
                        return record;
                    }
                }
            }
        }
        return null;
    }

    // --- failure handling -----------------------------------------------------

    /** A publisher whose broker always rejects the send, to exercise the retry path. */
    private OutboxPublisher failingPublisher(int maxAttempts) {
        @SuppressWarnings("unchecked")
        KafkaTemplate<String, String> broken = Mockito.mock(KafkaTemplate.class);
        Mockito.when(broken.send(Mockito.anyString(), Mockito.anyString(), Mockito.anyString()))
                .thenReturn(
                        CompletableFuture.failedFuture(new IllegalStateException("broker down")));

        OutboxProperties properties = new OutboxProperties();
        properties.setMaxAttempts(maxAttempts);
        properties.setSendTimeout(Duration.ofSeconds(2));

        return new OutboxPublisher(
                jdbc, broken, new TransactionTemplate(transactionManager), properties);
    }

    @Test
    @DisplayName("a failed send is retried later, not dropped and not marked published")
    void failedSendIsRetriedWithBackoff() {
        UUID userId = UUID.randomUUID();
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(userId);
        tx.executeWithoutResult(
                s -> recorder.record(Topics.AUTH_OTP_REQUESTED, "User", userId, envelope));

        assertThat(failingPublisher(10).publishDue()).isZero();

        assertThat(statusOf(envelope.eventId())).isEqualTo("PENDING");
        assertThat(attemptsOf(envelope.eventId())).isEqualTo(1);
        assertThat(lastErrorOf(envelope.eventId())).contains("broker down");
        // Backed off, so the very next pass leaves it alone rather than hammering the broker.
        assertThat(isDue(envelope.eventId())).isFalse();
    }

    @Test
    @DisplayName("a row that keeps failing is parked as FAILED for a human, never silently dropped")
    void permanentlyFailingRowIsParked() {
        UUID userId = UUID.randomUUID();
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(userId);
        tx.executeWithoutResult(
                s -> recorder.record(Topics.AUTH_OTP_REQUESTED, "User", userId, envelope));

        OutboxPublisher publisher = failingPublisher(1);
        publisher.publishDue();

        assertThat(statusOf(envelope.eventId())).isEqualTo("FAILED");
        assertThat(attemptsOf(envelope.eventId())).isEqualTo(1);
        // Parked rows are never retried automatically; they are an alert, not a queue.
        assertThat(publisher.publishDue()).isZero();
    }

    @Test
    void rowsNotYetDueAreLeftAlone() {
        UUID userId = UUID.randomUUID();
        EventEnvelope<OtpRequestedPayload> envelope = otpEvent(userId);
        tx.executeWithoutResult(
                s -> recorder.record(Topics.AUTH_OTP_REQUESTED, "User", userId, envelope));

        jdbc.sql("UPDATE outbox SET next_attempt_at = now() + interval '1 hour'").update();

        assertThat(publisher.publishDue()).isZero();
        assertThat(statusOf(envelope.eventId())).isEqualTo("PENDING");
    }

    private int attemptsOf(String eventId) {
        Integer v =
                jdbc.sql("SELECT attempts FROM outbox WHERE event_id = :id")
                        .param("id", eventId)
                        .query(Integer.class)
                        .single();
        return v == null ? 0 : v;
    }

    private String lastErrorOf(String eventId) {
        return jdbc.sql("SELECT last_error FROM outbox WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .single();
    }

    private boolean isDue(String eventId) {
        Boolean due =
                jdbc.sql("SELECT next_attempt_at <= now() FROM outbox WHERE event_id = :id")
                        .param("id", eventId)
                        .query(Boolean.class)
                        .single();
        return Boolean.TRUE.equals(due);
    }
}
