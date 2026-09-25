package com.pos.reporting;

import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.time.LocalDate;
import java.util.ArrayList;
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

import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.catalog.ProductChangedPayload;
import com.pos.events.inventory.BatchExpiringPayload;
import com.pos.events.inventory.StockDeductedPayload;
import com.pos.events.inventory.StockValuedPayload;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.events.sales.SaleVoidedPayload;
import com.pos.events.sales.ShiftClosedPayload;
import com.pos.reporting.domain.policy.BusinessDates;

/**
 * A real PostgreSQL, a real Kafka broker, and a day's trading as the other services announce it.
 *
 * <p>{@link #aDayOfTrading()} is one shift at one branch, built so the numbers can be worked out by
 * hand - and so the till's closing figures are exactly what the till's own arithmetic produces.
 */
@SpringBootTest
@AutoConfigureMockMvc
@ActiveProfiles("test")
public abstract class ReportingTestBase {

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

    protected static final UUID BRANCH = UUID.fromString("018f3a1c-0000-7000-8000-00000000b001");
    protected static final UUID OTHER_BRANCH =
            UUID.fromString("018f3a1c-0000-7000-8000-00000000b002");
    protected static final UUID REGISTER = UUID.fromString("018f3a1c-0000-7000-8000-00000000c001");
    protected static final UUID CASHIER = UUID.fromString("018f3a1c-0000-7000-8000-00000000a001");

    protected static final UUID SOAP = UUID.fromString("018f3a1c-0000-7000-8000-0000000d0001");
    protected static final UUID FLOUR = UUID.fromString("018f3a1c-0000-7000-8000-0000000d0002");
    protected static final UUID DUSTY = UUID.fromString("018f3a1c-0000-7000-8000-0000000d0003");
    protected static final UUID HOUSEHOLD = UUID.fromString("018f3a1c-0000-7000-8000-0000000ca001");
    protected static final UUID FOOD = UUID.fromString("018f3a1c-0000-7000-8000-0000000ca002");

    @Autowired protected KafkaTemplate<String, String> kafka;
    @Autowired protected JdbcClient jdbc;
    @Autowired protected MockMvc mockMvc;

    @BeforeEach
    void cleanSlate() {
        jdbc.sql(
                        """
                        TRUNCATE event_log, rebuild_runs, report_products, report_sales,
                                 report_sale_lines, report_sale_tenders, report_sale_costs,
                                 report_sale_voids, report_returns, report_return_lines,
                                 report_shifts, report_stock_valuations, report_expiring_batches,
                                 report_stock_adjustments, report_expenses
                        """)
                .update();
    }

    protected static LocalDate today() {
        return BusinessDates.of(Instant.now());
    }

    // --- tokens -----------------------------------------------------------------

    protected static RequestPostProcessor at(UUID branch, String... permissions) {
        UUID user = UUID.randomUUID();
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

    protected static RequestPostProcessor headOffice() {
        return at(BRANCH, "report:view", "report:view:branch", "export:data");
    }

    protected static RequestPostProcessor branchManager() {
        return at(BRANCH, "report:view:branch");
    }

    // --- a day of trading -----------------------------------------------------------

    /** One event on its topic, keyed as its producer keys it. */
    protected record Published(String topic, UUID key, EventEnvelope<?> envelope) {}

    protected final UUID shift = UUID.randomUUID();
    protected final UUID saleOne = UUID.randomUUID();
    protected final UUID saleTwo = UUID.randomUUID();
    protected final UUID saleThree = UUID.randomUUID();
    protected final UUID returnOne = UUID.randomUUID();
    protected final UUID adjustmentOne = UUID.randomUUID();
    protected final UUID countOne = UUID.randomUUID();

    /**
     * One shift, worked by hand:
     *
     * <ul>
     *   <li>Sale one: 3 soap at 116 (tax-inclusive, 16%), cash. Cost 3 x 80.
     *   <li>Sale two: 1 flour at 210 (zero-rated), 60 cash and 150 M-Pesa. Cost 150.
     *   <li>Sale three: 1 soap at 116, cash - then voided.
     *   <li>A return of one soap from sale one, resaleable, 116 cash from this shift's drawer.
     *   <li>Close: float 1000, one drop of 100, counted 1190.
     * </ul>
     *
     * <p>The till's own figures: cash sales 348 + 60 + 116 = 524; cash refunds 116 (the void) + 116
     * (the return) = 232; non-cash 150; expected 1000 + 524 - 232 - 100 = 1192; variance -2.
     *
     * <p>Returned in an order chosen to be awkward: costs before their sales, the void before the
     * sale it voids, the shift's close before anything else.
     */
    protected List<Published> aDayOfTrading() {
        Instant now = Instant.now();
        List<Published> events = new ArrayList<>();

        events.add(
                event(
                        Topics.SALES_SHIFT_CLOSED,
                        shift,
                        new ShiftClosedPayload(
                                shift,
                                BRANCH,
                                REGISTER,
                                CASHIER,
                                CASHIER,
                                now.minusSeconds(3600),
                                now,
                                money("1000.00"),
                                money("524.00"),
                                money("232.00"),
                                money("100.00"),
                                money("1192.00"),
                                money("1190.00"),
                                money("-2.00"),
                                money("150.00"),
                                3,
                                "KES")));
        events.add(
                event(
                        Topics.INVENTORY_STOCK_DEDUCTED,
                        saleOne,
                        deducted(saleOne, SOAP, "3", "80.00")));
        events.add(
                event(
                        Topics.INVENTORY_STOCK_DEDUCTED,
                        saleTwo,
                        deducted(saleTwo, FLOUR, "1", "150.00")));
        events.add(
                event(
                        Topics.SALES_SALE_VOIDED,
                        saleThree,
                        new SaleVoidedPayload(
                                saleThree,
                                "R-000003",
                                BRANCH,
                                REGISTER,
                                CASHIER,
                                CASHIER,
                                "VOID",
                                "Wrong item",
                                money("116.00"),
                                "KES",
                                now)));
        events.add(
                event(
                        Topics.SALES_SALE_COMPLETED,
                        saleOne,
                        sale(
                                saleOne,
                                "R-000001",
                                SOAP,
                                "SOAP",
                                "3",
                                "348.00",
                                "48.00",
                                List.of(tender(PaymentMethod.CASH, "348.00")))));
        events.add(
                event(
                        Topics.SALES_SALE_COMPLETED,
                        saleTwo,
                        sale(
                                saleTwo,
                                "R-000002",
                                FLOUR,
                                "FLOUR",
                                "1",
                                "210.00",
                                "0.00",
                                List.of(
                                        tender(PaymentMethod.CASH, "60.00"),
                                        tender(PaymentMethod.MPESA, "150.00")))));
        events.add(
                event(
                        Topics.SALES_SALE_COMPLETED,
                        saleThree,
                        sale(
                                saleThree,
                                "R-000003",
                                SOAP,
                                "SOAP",
                                "1",
                                "116.00",
                                "16.00",
                                List.of(tender(PaymentMethod.CASH, "116.00")))));
        events.add(
                event(
                        Topics.SALES_RETURN_PROCESSED,
                        saleOne,
                        new ReturnProcessedPayload(
                                returnOne,
                                saleOne,
                                BRANCH,
                                CASHIER,
                                now,
                                List.of(
                                        new ReturnProcessedPayload.ReturnLine(
                                                SOAP,
                                                "SOAP",
                                                money("1"),
                                                true,
                                                null,
                                                "CHANGED_MIND")),
                                money("116.00"),
                                "KES",
                                PaymentMethod.CASH,
                                shift)));
        events.add(product(SOAP, "SOAP", "Bath soap", HOUSEHOLD, "HOUSEHOLD"));
        events.add(product(FLOUR, "FLOUR", "Maize flour", FOOD, "FOOD"));
        events.add(product(DUSTY, "DUSTY", "Tinned something", FOOD, "FOOD"));

        // Stock at close, in two pages, the second first.
        UUID snapshot = UUID.randomUUID();
        events.add(
                event(
                        Topics.INVENTORY_STOCK_VALUED,
                        BRANCH,
                        new StockValuedPayload(
                                snapshot,
                                BRANCH,
                                now,
                                2,
                                2,
                                List.of(valued(DUSTY, "DUSTY", "4", "400.00")))));
        events.add(
                event(
                        Topics.INVENTORY_STOCK_VALUED,
                        BRANCH,
                        new StockValuedPayload(
                                snapshot,
                                BRANCH,
                                now,
                                1,
                                2,
                                List.of(
                                        valued(SOAP, "SOAP", "20", "1600.00"),
                                        valued(FLOUR, "FLOUR", "5", "750.00")))));
        events.add(
                event(
                        Topics.INVENTORY_BATCH_EXPIRING,
                        UUID.randomUUID(),
                        new BatchExpiringPayload(
                                UUID.randomUUID(),
                                "S-EXP",
                                SOAP,
                                "SOAP",
                                "Bath soap",
                                BRANCH,
                                today().plusDays(3),
                                3,
                                money("3"),
                                money("240.00"),
                                "KES")));
        // Shrinkage: two bars of soap damaged (and one found again), a bag of flour missing at the
        // count.
        events.add(
                event(
                        Topics.INVENTORY_ADJUSTMENT_POSTED,
                        adjustmentOne,
                        new com.pos.events.inventory.AdjustmentPostedPayload(
                                adjustmentOne,
                                BRANCH,
                                "DAMAGE",
                                CASHIER,
                                now,
                                "Dropped in the aisle",
                                List.of(
                                        new com.pos.events.inventory.AdjustmentPostedPayload
                                                .AdjustmentLine(
                                                SOAP,
                                                "SOAP-1",
                                                money("-2"),
                                                null,
                                                money("-160.00"),
                                                "KES"),
                                        new com.pos.events.inventory.AdjustmentPostedPayload
                                                .AdjustmentLine(
                                                SOAP,
                                                "SOAP-1",
                                                money("1"),
                                                null,
                                                money("0"),
                                                "KES")))));
        events.add(
                event(
                        Topics.INVENTORY_ADJUSTMENT_POSTED,
                        countOne,
                        new com.pos.events.inventory.AdjustmentPostedPayload(
                                countOne,
                                BRANCH,
                                "STOCK_TAKE",
                                CASHIER,
                                now,
                                "Stock take ST-1",
                                List.of(
                                        new com.pos.events.inventory.AdjustmentPostedPayload
                                                .AdjustmentLine(
                                                FLOUR,
                                                "FLOUR-2KG",
                                                money("-1"),
                                                null,
                                                money("-90.00"),
                                                "KES")))));
        return events;
    }

    protected void publishAll(List<Published> events) {
        for (Published published : events) {
            publish(published);
        }
        long expected = events.size();
        eventually(
                Duration.ofSeconds(60), "every event to be taken in", () -> logged() >= expected);
    }

    protected void publish(Published published) {
        try {
            kafka.send(
                            published.topic(),
                            published.key().toString(),
                            EventJson.write(published.envelope()))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish " + published.topic(), e);
        }
    }

    protected long logged() {
        Long count = jdbc.sql("SELECT count(*) FROM event_log").query(Long.class).single();
        return count == null ? 0 : count;
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

    // --- builders ---------------------------------------------------------------------

    protected static <T> Published event(String topic, UUID key, T payload) {
        return new Published(
                topic,
                key,
                EventEnvelope.<T>builder().topic(topic).branchId(BRANCH).payload(payload).build());
    }

    private static Published product(UUID id, String sku, String name, UUID category, String code) {
        return event(
                Topics.CATALOG_PRODUCT_CHANGED,
                id,
                new ProductChangedPayload(
                        id,
                        sku,
                        name,
                        category,
                        code,
                        "EACH",
                        "VAT16",
                        false,
                        true,
                        money("0"),
                        "KES"));
    }

    private SaleCompletedPayload sale(
            UUID saleId,
            String receipt,
            UUID product,
            String sku,
            String quantity,
            String total,
            String tax,
            List<SaleCompletedPayload.Tender> tenders) {
        BigDecimal grand = money(total);
        BigDecimal taxAmount = money(tax);
        return new SaleCompletedPayload(
                saleId,
                receipt,
                BRANCH,
                REGISTER,
                shift,
                CASHIER,
                null,
                Instant.now(),
                List.of(
                        new SaleCompletedPayload.SaleLine(
                                product,
                                sku,
                                sku,
                                money(quantity),
                                grand.divide(money(quantity), 4, java.math.RoundingMode.HALF_UP),
                                grand,
                                taxAmount,
                                "VAT16",
                                null)),
                grand.subtract(taxAmount),
                taxAmount,
                grand,
                "KES",
                null,
                tenders);
    }

    private static SaleCompletedPayload.Tender tender(PaymentMethod method, String amount) {
        return new SaleCompletedPayload.Tender(method, money(amount));
    }

    private static StockDeductedPayload deducted(
            UUID saleId, UUID product, String quantity, String unitCost) {
        return deducted(saleId, product, quantity, quantity, unitCost);
    }

    /** A deduction where only {@code fromBatches} of the line came out of a batch. */
    protected static StockDeductedPayload deducted(
            UUID saleId, UUID product, String quantity, String fromBatches, String unitCost) {
        return new StockDeductedPayload(
                saleId,
                BRANCH,
                Instant.now(),
                List.of(
                        new StockDeductedPayload.DeductedLine(
                                product,
                                money(quantity),
                                money("10"),
                                List.of(
                                        new StockDeductedPayload.BatchAllocation(
                                                UUID.randomUUID(),
                                                "B-1",
                                                money(fromBatches),
                                                money(unitCost))))));
    }

    private static StockValuedPayload.ValuedLine valued(
            UUID product, String sku, String quantity, String value) {
        return new StockValuedPayload.ValuedLine(
                product, sku, money(quantity), money(value), "KES");
    }

    protected static BigDecimal money(String value) {
        return new BigDecimal(value);
    }
}
