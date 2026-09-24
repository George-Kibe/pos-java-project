package com.pos.sales;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.Arrays;
import java.util.List;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.AfterEach;
import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.core.context.SecurityContextHolder;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.security.oauth2.server.resource.authentication.JwtAuthenticationToken;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;

/**
 * Shared setup: a real PostgreSQL, a real Kafka broker, and fakes for the two services sales calls.
 *
 * <p>The services under test read the caller from the security context exactly as they do in
 * production, so each test acts as a named user with named permissions rather than bypassing the
 * lookup.
 */
@SpringBootTest
@ActiveProfiles("test")
public abstract class SalesTestBase {

    // Never closed on purpose: it lives for the whole JVM and Testcontainers' reaper removes it.

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:8.3.2");

    static final FakeCatalog CATALOG = new FakeCatalog();
    static final FakeInventory INVENTORY = new FakeInventory();

    static {
        // Started once and never stopped: @Container would stop them while Spring's context cache
        // still hands out the ports.
        POSTGRES.start();
        KAFKA.start();
    }

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("pos.sales.catalog-uri", CATALOG::baseUrl);
        registry.add("pos.sales.inventory-uri", INVENTORY::baseUrl);
    }

    protected static final UUID BRANCH = UUID.fromString("018f3a1c-0000-7000-8000-00000000b001");
    protected static final UUID REGISTER = UUID.fromString("018f3a1c-0000-7000-8000-00000000c001");
    protected static final UUID CASHIER = UUID.fromString("018f3a1c-0000-7000-8000-00000000a001");
    protected static final UUID SUPERVISOR =
            UUID.fromString("018f3a1c-0000-7000-8000-00000000a002");
    protected static final String TOKEN = "Bearer test-token";

    // The basket the roadmap asks for: standard-rated, zero-rated, weighed, promo-discounted.
    protected static final FakeCatalog.Product SOAP =
            new FakeCatalog.Product(
                    UUID.fromString("018f3a1c-0000-7000-8000-0000000d0001"),
                    "SOAP-200G",
                    "Bath soap 200g",
                    new BigDecimal("116.00"),
                    "VAT16",
                    new BigDecimal("0.16"),
                    true,
                    null);
    protected static final FakeCatalog.Product FLOUR =
            new FakeCatalog.Product(
                    UUID.fromString("018f3a1c-0000-7000-8000-0000000d0002"),
                    "MAIZE-2KG",
                    "Maize flour 2kg",
                    new BigDecimal("210.00"),
                    "ZERO",
                    BigDecimal.ZERO,
                    true,
                    null);
    protected static final FakeCatalog.Product BANANAS =
            new FakeCatalog.Product(
                    UUID.fromString("018f3a1c-0000-7000-8000-0000000d0003"),
                    "BANANA-KG",
                    "Bananas per kg",
                    new BigDecimal("129.99"),
                    "VAT16",
                    new BigDecimal("0.16"),
                    true,
                    null);
    protected static final FakeCatalog.Product JUICE =
            new FakeCatalog.Product(
                    UUID.fromString("018f3a1c-0000-7000-8000-0000000d0004"),
                    "JUICE-1L",
                    "Mango juice 1L",
                    new BigDecimal("250.00"),
                    "VAT16",
                    new BigDecimal("0.16"),
                    true,
                    new BigDecimal("25.00"));

    @Autowired protected KafkaTemplate<String, String> kafka;
    @Autowired protected JdbcClient jdbc;
    @Autowired private com.pos.sales.service.TillSessionService shiftsForHandover;

    @BeforeEach
    void cleanSlate() {
        // Children before parents.
        for (String table :
                List.of(
                        "sale_cash_denominations",
                        "drawer_movements",
                        "till_session_counts",
                        "intraday_movements",
                        "cash_limits",
                        "registers",
                        "receipts",
                        "return_lines",
                        "returns",
                        "sale_payments",
                        "sale_lines",
                        "price_overrides",
                        "sales",
                        "cart_lines",
                        "carts",
                        "cash_movements",
                        "till_sessions",
                        "receipt_sequences",
                        "offline_sync_batches",
                        "idempotency_records",
                        "processed_event",
                        "outbox")) {
            jdbc.sql("DELETE FROM sales." + table).update();
        }
        CATALOG.reset();
        INVENTORY.reset();
        for (FakeCatalog.Product product : List.of(SOAP, FLOUR, BANANAS, JUICE)) {
            CATALOG.register(product);
        }
        actingAs(CASHIER, CASHIER_PERMISSIONS);
    }

    @AfterEach
    void signOut() {
        SecurityContextHolder.clearContext();
    }

    protected static final String[] CASHIER_PERMISSIONS = {
        "shift:open", "shift:close", "cart:manage", "sale:create", "payment:take", "product:view"
    };

    protected static final String[] SUPERVISOR_PERMISSIONS = {
        "shift:open",
        "shift:close",
        "shift:close:any",
        "cash:drop",
        "cash:intraday",
        "cart:manage",
        "sale:create",
        "sale:void",
        "sale:refund",
        "payment:take",
        "price:override",
        "product:view",
        "report:view:branch"
    };

    /**
     * The close's handover: stops the till if it is still selling, and a supervisor receives {@code
     * cash} - then the caller is put back, so the close is theirs.
     */
    protected void handOver(UUID tillId, java.math.BigDecimal cash) {
        var caller = SecurityContextHolder.getContext().getAuthentication();
        if (shiftsForHandover.require(tillId).getStatus()
                == com.pos.sales.domain.TillSessionStatus.OPEN) {
            shiftsForHandover.beginClose(tillId);
        }
        actingAs(SUPERVISOR, SUPERVISOR_PERMISSIONS);
        try {
            shiftsForHandover.handOver(tillId, cash, null, null);
        } finally {
            SecurityContextHolder.getContext().setAuthentication(caller);
        }
    }

    /** Puts a verified-looking token in the security context, as the resource server would. */
    protected static void actingAs(UUID userId, String... permissions) {
        Jwt jwt =
                Jwt.withTokenValue("test-token")
                        .header("alg", "RS256")
                        .subject(userId.toString())
                        .claim("uid", userId.toString())
                        .claim("perms", List.of(permissions))
                        .claim("branches", List.of(BRANCH.toString()))
                        .build();
        List<GrantedAuthority> authorities =
                Arrays.stream(permissions)
                        .map(SimpleGrantedAuthority::new)
                        .map(GrantedAuthority.class::cast)
                        .toList();
        SecurityContextHolder.getContext()
                .setAuthentication(new JwtAuthenticationToken(jwt, authorities));
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
                jdbc.sql("SELECT count(*) FROM sales.outbox WHERE topic = :topic")
                        .param("topic", topic)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    protected String latestOutboxPayload(String topic) {
        return jdbc.sql(
                        """
                        SELECT payload FROM sales.outbox
                        WHERE topic = :topic ORDER BY created_at DESC LIMIT 1
                        """)
                .param("topic", topic)
                .query(String.class)
                .single();
    }

    protected static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
