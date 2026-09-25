package com.pos.notification;

import java.io.IOException;
import java.net.ServerSocket;
import java.time.Duration;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;

/**
 * Shared setup: a real PostgreSQL, a real Kafka broker, and events published exactly as
 * auth-service's outbox relay publishes them.
 *
 * <p>Publishing real events onto a real topic is the point. Calling the listener method directly
 * would skip deserialisation, the idempotency ledger, the retry policy and the dead-letter route -
 * which between them are most of what this service is.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class NotificationTestBase {

    /** Started once for the JVM. See the note in the auth test base about @Container. */
    // Never closed on purpose: it lives for the whole JVM and Testcontainers' reaper removes it.
    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:8.3.2");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    @Autowired protected KafkaTemplate<String, String> kafka;
    @Autowired protected JdbcClient jdbc;

    @BeforeEach
    void clearLedgers() {
        // Each test asserts on its own rows; leftovers from an earlier test would make counts lie.
        jdbc.sql(
                        "TRUNCATE notification.notification_log, notification.processed_event,"
                                + " notification.template_texts")
                .update();
    }

    /** Publishes an event the way the outbox relay does: envelope JSON, keyed by aggregate id. */
    protected void publish(String topic, EventEnvelope<?> envelope, UUID aggregateId) {
        try {
            kafka.send(topic, aggregateId.toString(), EventJson.write(envelope))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish " + topic, e);
        }
    }

    /** Polls until the condition holds, because consumption is asynchronous. */
    protected void eventually(Duration timeout, String description, BooleanSupplier condition) {
        long deadline = System.currentTimeMillis() + timeout.toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            try {
                Thread.sleep(200);
            } catch (InterruptedException e) {
                Thread.currentThread().interrupt();
                throw new IllegalStateException(e);
            }
        }
        throw new AssertionError("Timed out waiting for: " + description);
    }

    protected String statusOf(String eventId) {
        return jdbc.sql("SELECT status FROM notification.notification_log WHERE event_id = :id")
                .param("id", eventId)
                .query(String.class)
                .optional()
                .orElse(null);
    }

    protected long logCount() {
        Long count =
                jdbc.sql("SELECT count(*) FROM notification.notification_log")
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    protected static int freePort() {
        try (ServerSocket socket = new ServerSocket(0)) {
            return socket.getLocalPort();
        } catch (IOException e) {
            throw new IllegalStateException("No free port for the test SMTP server", e);
        }
    }
}
