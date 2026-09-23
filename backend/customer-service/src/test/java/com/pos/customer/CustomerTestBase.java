package com.pos.customer;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.pos.customer.domain.Customer;
import com.pos.customer.service.CustomerService;
import com.pos.customer.service.LoyaltyService;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.events.sales.SaleCompletedPayload;

/** A real PostgreSQL and a real Kafka broker; the sweeps are driven by the tests. */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class CustomerTestBase {

    // Never closed on purpose: it lives for the whole JVM and Testcontainers' reaper removes it.

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    static {
        // Started once and never stopped: Spring's context cache outlives any one test class.
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

    protected static final UUID BRANCH = UUID.fromString("018f3a1c-0000-7000-8000-00000000b001");
    protected static final UUID REGISTER = UUID.fromString("018f3a1c-0000-7000-8000-00000000c001");
    protected static final UUID CASHIER = UUID.fromString("018f3a1c-0000-7000-8000-00000000a001");
    protected static final UUID MANAGER = UUID.fromString("018f3a1c-0000-7000-8000-00000000a002");

    @Autowired protected KafkaTemplate<String, String> kafka;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected CustomerService customers;
    @Autowired protected LoyaltyService loyalty;

    @BeforeEach
    void cleanSlate() {
        for (String table :
                List.of(
                        "loyalty_transactions",
                        "loyalty_accounts",
                        "customer_consents",
                        "customer_addresses",
                        "customers",
                        "idempotency_records",
                        "processed_event",
                        "outbox")) {
            jdbc.sql("DELETE FROM customer." + table).update();
        }
        // membership_tiers is seeded by the migration and is configuration, not test data.
    }

    // --- tokens -----------------------------------------------------------------

    protected static RequestPostProcessor at(UUID user, String... permissions) {
        GrantedAuthority[] authorities =
                Arrays.stream(permissions)
                        .map(SimpleGrantedAuthority::new)
                        .toArray(GrantedAuthority[]::new);
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .claim("branches", List.of(BRANCH.toString()))
                                .build())
                .authorities(authorities);
    }

    protected static RequestPostProcessor cashier() {
        return at(CASHIER, "customer:view");
    }

    protected static RequestPostProcessor manager() {
        return at(MANAGER, "customer:view", "customer:manage", "loyalty:adjust");
    }

    // --- members ----------------------------------------------------------------

    protected Customer enrol(String firstName, String phone) {
        return customers.create(
                new CustomerService.Details(
                        firstName, "Wanjiru", phone, null, null, null, null, null));
    }

    // --- events from the tills -----------------------------------------------------

    protected EventEnvelope<SaleCompletedPayload> saleCompleted(
            UUID customerId, UUID saleId, String grandTotal) {
        BigDecimal grand = new BigDecimal(grandTotal);
        return EventEnvelope.<SaleCompletedPayload>builder()
                .topic(Topics.SALES_SALE_COMPLETED)
                .correlationId("corr-" + saleId)
                .branchId(BRANCH)
                .payload(
                        new SaleCompletedPayload(
                                saleId,
                                "R-000001",
                                BRANCH,
                                REGISTER,
                                UUID.randomUUID(),
                                CASHIER,
                                customerId,
                                Instant.now(),
                                List.of(),
                                grand.multiply(new BigDecimal("0.862069")),
                                grand.multiply(new BigDecimal("0.137931")),
                                grand,
                                "KES",
                                null,
                                java.util.List.of()))
                .build();
    }

    protected EventEnvelope<PaymentRequestedPayload> loyaltyTender(
            UUID customerId, UUID saleId, UUID intentId, String amount) {
        return EventEnvelope.<PaymentRequestedPayload>builder()
                .topic(Topics.PAYMENTS_PAYMENT_REQUESTED)
                .correlationId("corr-" + saleId)
                .branchId(BRANCH)
                .actorId(CASHIER)
                .payload(
                        new PaymentRequestedPayload(
                                intentId,
                                saleId,
                                "R-000002",
                                BRANCH,
                                REGISTER,
                                CASHIER,
                                PaymentMethod.LOYALTY,
                                new BigDecimal(amount),
                                "KES",
                                null,
                                null,
                                Instant.now(),
                                customerId))
                .build();
    }

    protected void publish(String topic, EventEnvelope<?> envelope, UUID key) {
        try {
            kafka.send(topic, key.toString(), EventJson.write(envelope)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish " + topic, e);
        }
    }

    /** Publishes and waits for the consumer to have recorded it. */
    protected void publishAndAwait(String topic, EventEnvelope<?> envelope, UUID key) {
        publish(topic, envelope, key);
        eventually(
                Duration.ofSeconds(30),
                "the event to be handled",
                () -> handled(envelope.eventId()));
    }

    protected boolean handled(String eventId) {
        Long count =
                jdbc.sql("SELECT count(*) FROM customer.processed_event WHERE event_id = :id")
                        .param("id", eventId)
                        .query(Long.class)
                        .single();
        return count != null && count > 0;
    }

    protected long outboxCount(String topic) {
        Long count =
                jdbc.sql("SELECT count(*) FROM customer.outbox WHERE topic = :topic")
                        .param("topic", topic)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    protected String latestOutboxPayload(String topic) {
        return jdbc.sql(
                        """
                        SELECT payload FROM customer.outbox
                        WHERE topic = :topic ORDER BY created_at DESC LIMIT 1
                        """)
                .param("topic", topic)
                .query(String.class)
                .single();
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
}
