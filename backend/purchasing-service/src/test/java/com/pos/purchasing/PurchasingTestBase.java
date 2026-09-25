package com.pos.purchasing;

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
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.repository.GoodsReceivedNoteRepository;
import com.pos.purchasing.repository.PurchaseOrderRepository;
import com.pos.purchasing.repository.SupplierInvoiceRepository;
import com.pos.purchasing.repository.SupplierRepository;

/**
 * Shared setup: a real PostgreSQL and a real Kafka broker.
 *
 * <p>Events go onto real topics rather than into a listener called directly, so deserialisation,
 * the idempotency ledger and the retry policy are exercised too - which between them are most of
 * what makes a redelivered event harmless.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class PurchasingTestBase {

    /** Started once for the JVM; @Container would stop it while Spring still hands out its port. */
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
    @Autowired protected SupplierRepository suppliers;
    @Autowired protected PurchaseOrderRepository orders;
    @Autowired protected GoodsReceivedNoteRepository grns;
    @Autowired protected SupplierInvoiceRepository invoices;

    @BeforeEach
    void clearPurchasing() {
        // Children before parents.
        jdbc.sql("DELETE FROM purchasing.supplier_return_lines").update();
        jdbc.sql("DELETE FROM purchasing.supplier_returns").update();
        jdbc.sql("DELETE FROM purchasing.supplier_invoice_variances").update();
        jdbc.sql("DELETE FROM purchasing.supplier_invoices").update();
        jdbc.sql("DELETE FROM purchasing.grn_lines").update();
        jdbc.sql("DELETE FROM purchasing.goods_received_notes").update();
        jdbc.sql("DELETE FROM purchasing.reorder_suggestions").update();
        jdbc.sql("DELETE FROM purchasing.purchase_order_lines").update();
        jdbc.sql("DELETE FROM purchasing.purchase_orders").update();
        jdbc.sql("DELETE FROM purchasing.supplier_products").update();
        jdbc.sql("DELETE FROM purchasing.suppliers").update();
        jdbc.sql("DELETE FROM purchasing.processed_event").update();
        jdbc.sql("DELETE FROM purchasing.outbox").update();
    }

    /** A saved supplier, since almost everything here hangs off one. */
    protected Supplier givenSupplier(String code, String name) {
        Supplier supplier = new Supplier(code, name);
        supplier.setPaymentTermsDays(30);
        supplier.setLeadTimeDays(7);
        return suppliers.save(supplier);
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

    protected long outboxCount(String topic) {
        Long count =
                jdbc.sql("SELECT count(*) FROM purchasing.outbox WHERE topic = :topic")
                        .param("topic", topic)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    /** The payload of the single outbox row on a topic, as raw JSON. */
    protected String outboxPayload(String topic) {
        return jdbc.sql(
                        """
                        SELECT payload FROM purchasing.outbox
                        WHERE topic = :topic ORDER BY created_at DESC LIMIT 1
                        """)
                .param("topic", topic)
                .query(String.class)
                .single();
    }

    protected long suggestionCount() {
        Long count =
                jdbc.sql("SELECT count(*) FROM purchasing.reorder_suggestions")
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    protected static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
