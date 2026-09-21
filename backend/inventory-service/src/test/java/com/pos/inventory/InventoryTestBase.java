package com.pos.inventory;

import java.math.BigDecimal;
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
import com.pos.inventory.repository.StockBatchRepository;
import com.pos.inventory.repository.StockItemRepository;
import com.pos.inventory.repository.StockMovementRepository;

/**
 * Shared setup: a real PostgreSQL and a real Kafka broker.
 *
 * <p>Events are published onto real topics rather than by calling the listener directly, so
 * deserialisation, the idempotency ledger and the retry policy are all exercised - which between
 * them are most of what makes a redelivered sale harmless.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class InventoryTestBase {

    /** Started once for the JVM; see the note in the auth test base about @Container. */
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

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
    @Autowired protected StockItemRepository items;
    @Autowired protected StockBatchRepository batches;
    @Autowired protected StockMovementRepository movements;

    @BeforeEach
    void clearStock() {
        // Order matters: movements and batches reference items.
        jdbc.sql("DELETE FROM inventory.stock_take_lines").update();
        jdbc.sql("DELETE FROM inventory.stock_takes").update();
        jdbc.sql("DELETE FROM inventory.stock_adjustment_lines").update();
        jdbc.sql("DELETE FROM inventory.stock_adjustments").update();
        jdbc.sql("DELETE FROM inventory.stock_transfer_lines").update();
        jdbc.sql("DELETE FROM inventory.stock_transfers").update();
        jdbc.sql("DELETE FROM inventory.stock_reservations").update();
        jdbc.sql("DELETE FROM inventory.stock_movements").update();
        jdbc.sql("DELETE FROM inventory.stock_batches").update();
        jdbc.sql("DELETE FROM inventory.stock_items").update();
        jdbc.sql("DELETE FROM inventory.processed_event").update();
        jdbc.sql("DELETE FROM inventory.outbox").update();
    }

    protected void publish(String topic, EventEnvelope<?> envelope, UUID key) {
        try {
            kafka.send(topic, key.toString(), EventJson.write(envelope)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish " + topic, e);
        }
    }

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

    protected BigDecimal onHand(UUID productId, UUID branchId) {
        return items.findByProductIdAndBranchId(productId, branchId)
                .map(item -> item.getQuantityOnHand())
                .orElse(null);
    }

    /** What the ledger says, independently of the cached figure. */
    protected BigDecimal ledgerTotal(UUID productId, UUID branchId) {
        return jdbc.sql(
                        """
                        SELECT COALESCE(SUM(m.quantity), 0)
                        FROM inventory.stock_movements m
                        JOIN inventory.stock_items i ON i.id = m.stock_item_id
                        WHERE i.product_id = :productId AND i.branch_id = :branchId
                        """)
                .param("productId", productId)
                .param("branchId", branchId)
                .query(BigDecimal.class)
                .single();
    }

    protected long movementCount(UUID productId, UUID branchId) {
        Long count =
                jdbc.sql(
                                """
                                SELECT count(*) FROM inventory.stock_movements m
                                JOIN inventory.stock_items i ON i.id = m.stock_item_id
                                WHERE i.product_id = :productId AND i.branch_id = :branchId
                                """)
                        .param("productId", productId)
                        .param("branchId", branchId)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    protected long outboxCount(String topic) {
        Long count =
                jdbc.sql("SELECT count(*) FROM inventory.outbox WHERE topic = :topic")
                        .param("topic", topic)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }
}
