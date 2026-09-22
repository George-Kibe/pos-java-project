package com.pos.payment;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.Arrays;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.BeforeEach;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.ActiveProfiles;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.ResultActions;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.service.PaymentDispatcher;
import com.pos.payment.service.PaymentIntentService;

/**
 * A real PostgreSQL, a real Kafka broker and a fake Daraja.
 *
 * <p>The dispatcher and sweeps are driven explicitly (their schedules are an hour apart in the test
 * profile), so nothing happens behind an assertion's back.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class PaymentTestBase {

    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:16-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:7.8.0");

    static final FakeDaraja DARAJA = new FakeDaraja();

    protected static final String CALLBACK_TOKEN = "test-callback-token";

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
        // Test values pointed at the fake, not credentials.
        registry.add("pos.payment.mpesa.base-url", DARAJA::baseUrl);
        registry.add("pos.payment.mpesa.consumer-key", () -> "test-key");
        registry.add("pos.payment.mpesa.consumer-secret", () -> "test-secret");
        registry.add("pos.payment.mpesa.shortcode", () -> "600000");
        registry.add("pos.payment.mpesa.passkey", () -> "test-passkey");
        registry.add("pos.payment.mpesa.callback-base-url", () -> "https://pos.example.test");
        registry.add("pos.payment.mpesa.callback-token", () -> CALLBACK_TOKEN);
        registry.add("pos.payment.mpesa.initiator-name", () -> "test-initiator");
        registry.add("pos.payment.mpesa.security-credential", () -> "test-credential");
        registry.add("pos.payment.mpesa.first-query-after", () -> "PT0S");
        registry.add("pos.payment.mpesa.query-interval", () -> "PT0S");
        registry.add("pos.payment.mpesa.max-query-attempts", () -> "3");
    }

    protected static final UUID BRANCH = UUID.fromString("018f3a1c-0000-7000-8000-00000000b001");
    protected static final UUID OTHER_BRANCH =
            UUID.fromString("018f3a1c-0000-7000-8000-00000000b002");
    protected static final UUID REGISTER = UUID.fromString("018f3a1c-0000-7000-8000-00000000c001");
    protected static final UUID CASHIER = UUID.fromString("018f3a1c-0000-7000-8000-00000000a001");
    protected static final UUID SUPERVISOR =
            UUID.fromString("018f3a1c-0000-7000-8000-00000000a002");

    @Autowired protected KafkaTemplate<String, String> kafka;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MockMvc mockMvc;
    @Autowired protected PaymentDispatcher dispatcher;
    @Autowired protected PaymentIntentService intents;

    @BeforeEach
    void cleanSlate() {
        for (String table :
                List.of(
                        "reconciliation_items",
                        "reconciliation_runs",
                        "refunds",
                        "payment_events",
                        "mpesa_transactions",
                        "payments",
                        "payment_intents",
                        "idempotency_records",
                        "processed_event",
                        "outbox")) {
            jdbc.sql("DELETE FROM payment." + table).update();
        }
        DARAJA.reset();
    }

    // --- tokens -----------------------------------------------------------------

    protected static RequestPostProcessor at(UUID branch, UUID user, String... permissions) {
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
                                .claim("branches", List.of(branch.toString()))
                                .build())
                .authorities(authorities);
    }

    protected static RequestPostProcessor cashier() {
        return at(BRANCH, CASHIER, "payment:take", "sale:create");
    }

    protected static RequestPostProcessor supervisor() {
        return at(BRANCH, SUPERVISOR, "payment:take", "sale:refund", "report:view:branch");
    }

    // --- requests from sales -------------------------------------------------------

    protected EventEnvelope<PaymentRequestedPayload> request(
            PaymentMethod method, String amount, String phone, String terminalReference) {
        UUID saleId = UUID.randomUUID();
        return requestFor(saleId, method, amount, phone, terminalReference);
    }

    protected EventEnvelope<PaymentRequestedPayload> requestFor(
            UUID saleId, PaymentMethod method, String amount, String phone, String terminalRef) {
        return EventEnvelope.<PaymentRequestedPayload>builder()
                .topic(Topics.PAYMENTS_PAYMENT_REQUESTED)
                .correlationId("corr-" + saleId)
                .branchId(BRANCH)
                .actorId(CASHIER)
                .payload(
                        new PaymentRequestedPayload(
                                UUID.randomUUID(),
                                saleId,
                                null,
                                BRANCH,
                                REGISTER,
                                CASHIER,
                                method,
                                new BigDecimal(amount),
                                "KES",
                                phone,
                                terminalRef,
                                Instant.now()))
                .build();
    }

    /** Sends a request through Kafka, waits for it to be accepted, then dispatches it. */
    protected PaymentIntent requestAndDispatch(EventEnvelope<PaymentRequestedPayload> request) {
        publish(Topics.PAYMENTS_PAYMENT_REQUESTED, request, request.payload().saleId());
        UUID id = request.payload().paymentIntentId();
        eventually(Duration.ofSeconds(30), "the request to be accepted", () -> exists(id));
        dispatcher.dispatchDue();
        return intents.require(id);
    }

    protected boolean exists(UUID intentId) {
        Long count =
                jdbc.sql("SELECT count(*) FROM payment.payment_intents WHERE id = :id")
                        .param("id", intentId)
                        .query(Long.class)
                        .single();
        return count != null && count > 0;
    }

    // --- Daraja calling back ---------------------------------------------------------

    protected ResultActions stkCallback(
            String checkoutRequestId, int resultCode, String receipt, String amount)
            throws Exception {
        return mockMvc.perform(
                post("/api/v1/payments/mpesa/callbacks/stk/" + CALLBACK_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(stkCallbackBody(checkoutRequestId, resultCode, receipt, amount)));
    }

    protected static String stkCallbackBody(
            String checkoutRequestId, int resultCode, String receipt, String amount) {
        String metadata =
                resultCode != 0
                        ? ""
                        : """
                          ,"CallbackMetadata":{"Item":[
                            {"Name":"Amount","Value":%s},
                            {"Name":"MpesaReceiptNumber","Value":"%s"},
                            {"Name":"Balance"},
                            {"Name":"TransactionDate","Value":20260922143011},
                            {"Name":"PhoneNumber","Value":254712345678}]}"""
                                .formatted(amount, receipt);
        return """
               {"Body":{"stkCallback":{"MerchantRequestID":"mr","CheckoutRequestID":"%s",\
               "ResultCode":%d,"ResultDesc":"%s"%s}}}"""
                .formatted(
                        checkoutRequestId,
                        resultCode,
                        resultCode == 0
                                ? "The service request is processed successfully."
                                : "Request cancelled by user",
                        metadata);
    }

    // --- Kafka and the outbox ----------------------------------------------------------

    protected void publish(String topic, EventEnvelope<?> envelope, UUID key) {
        try {
            kafka.send(topic, key.toString(), EventJson.write(envelope)).get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish " + topic, e);
        }
    }

    protected long outboxCount(String topic) {
        Long count =
                jdbc.sql("SELECT count(*) FROM payment.outbox WHERE topic = :topic")
                        .param("topic", topic)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }

    protected String latestOutboxPayload(String topic) {
        return jdbc.sql(
                        """
                        SELECT payload FROM payment.outbox
                        WHERE topic = :topic ORDER BY created_at DESC LIMIT 1
                        """)
                .param("topic", topic)
                .query(String.class)
                .single();
    }

    @SuppressWarnings("unchecked")
    protected Map<String, Object> latestOutboxPayloadField(String topic) {
        Map<String, Object> envelope =
                EventJson.mapper().readValue(latestOutboxPayload(topic), Map.class);
        return (Map<String, Object>) envelope.get("payload");
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
