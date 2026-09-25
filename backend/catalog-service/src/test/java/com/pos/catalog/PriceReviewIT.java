package com.pos.catalog;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.security.test.web.servlet.request.SecurityMockMvcRequestPostProcessors.jwt;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.put;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;
import java.util.concurrent.TimeUnit;
import java.util.function.BooleanSupplier;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.webmvc.test.autoconfigure.AutoConfigureMockMvc;
import org.springframework.http.MediaType;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.security.core.GrantedAuthority;
import org.springframework.security.core.authority.SimpleGrantedAuthority;
import org.springframework.security.oauth2.jwt.Jwt;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;
import org.springframework.test.web.servlet.MockMvc;
import org.springframework.test.web.servlet.request.RequestPostProcessor;
import org.testcontainers.kafka.ConfluentKafkaContainer;

import com.pos.catalog.domain.Product;
import com.pos.catalog.service.PriceListService;
import com.pos.events.EventEnvelope;
import com.pos.events.EventJson;
import com.pos.events.Topics;
import com.pos.events.purchasing.GoodsReceivedPayload;

/**
 * A delivery's cost against the price, end to end: purchasing's event in, a price review out, and a
 * manager settling it. Its own broker, and listeners on - the other catalog tests have neither.
 */
@AutoConfigureMockMvc
@DisplayName("Price reviews")
class PriceReviewIT extends CatalogTestBase {

    @SuppressWarnings("resource")
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:8.3.2");

    static {
        KAFKA.start();
    }

    @DynamicPropertySource
    static void kafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
        registry.add("spring.kafka.listener.auto-startup", () -> "true");
        // A fresh topic, and the listener may join after the first publish.
        registry.add("spring.kafka.consumer.auto-offset-reset", () -> "earliest");
    }

    private static final UUID BRANCH = UUID.randomUUID();
    private static final UUID OTHER_BRANCH = UUID.randomUUID();

    @Autowired private MockMvc mockMvc;
    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private PriceListService priceLists;

    private static RequestPostProcessor at(UUID branch, String... permissions) {
        UUID user = UUID.randomUUID();
        return jwt().jwt(
                        Jwt.withTokenValue("test")
                                .header("alg", "RS256")
                                .subject(user.toString())
                                .claim("uid", user.toString())
                                .claim("perms", List.of(permissions))
                                .claim("branches", List.of(branch.toString()))
                                .build())
                .authorities(
                        java.util.Arrays.stream(permissions)
                                .map(SimpleGrantedAuthority::new)
                                .toArray(GrantedAuthority[]::new));
    }

    private static final RequestPostProcessor MANAGER = at(BRANCH, "price:manage", "product:view");

    @Test
    @DisplayName(
            "a delivery below target opens one review however often it arrives, and accepting it"
                    + " sets the price")
    void aDeliveryBelowTargetOpensAReviewThatSetsThePrice() throws Exception {
        grocerySellsAtFifteenPercent();
        Product oil = newProduct("OIL-" + suffix(), "Cooking oil 1L", "STANDARD", "232", true);
        Product rice = newProduct("RICE-" + suffix(), "Rice 2kg", "STANDARD", "116", true);

        EventEnvelope<GoodsReceivedPayload> delivery = delivery(BRANCH, oil, "190");
        publish(delivery);
        publish(delivery);
        // Rice at a loss, after the duplicate on the same partition: once its review is there, the
        // duplicate has been seen too.
        publish(delivery(BRANCH, rice, "105"));
        eventually(() -> openReviews(rice) == 1);
        assertThat(openReviews(oil)).isEqualTo(1);

        mockMvc.perform(
                        get("/api/v1/price-reviews")
                                .param("branchId", BRANCH.toString())
                                .with(MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(2)));
        String oilReview =
                jdbc.sql("SELECT id FROM catalog.price_reviews WHERE product_id = ?")
                        .param(oil.getId())
                        .query(String.class)
                        .single();
        mockMvc.perform(get("/api/v1/price-reviews/" + oilReview).with(MANAGER))
                .andExpect(jsonPath("$.finding", is("BELOW_TARGET")))
                .andExpect(jsonPath("$.priceSource", is("BASE_PRICE")))
                .andExpect(jsonPath("$.suggestedPrice", is(260.0)));

        // Another branch's manager cannot settle it; this one takes the suggestion.
        mockMvc.perform(
                        post("/api/v1/price-reviews/" + oilReview + "/accept")
                                .with(at(OTHER_BRANCH, "price:manage")))
                .andExpect(status().isForbidden());
        mockMvc.perform(post("/api/v1/price-reviews/" + oilReview + "/accept").with(MANAGER))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("ACCEPTED")))
                .andExpect(jsonPath("$.newPrice", is(260.0)));
        assertThat(products.findById(oil.getId()).orElseThrow().getBasePrice())
                .isEqualByComparingTo("260");
        assertThat(
                        jdbc.sql(
                                        "SELECT count(*) FROM catalog.outbox WHERE topic = ?"
                                                + " AND aggregate_id = ?")
                                .param(Topics.CATALOG_PRICE_CHANGED)
                                .param(oil.getId())
                                .query(Long.class)
                                .single())
                .isEqualTo(1);
        mockMvc.perform(post("/api/v1/price-reviews/" + oilReview + "/accept").with(MANAGER))
                .andExpect(status().isConflict());
    }

    @Test
    @DisplayName(
            "a branch's list price is what is reviewed; a later delivery replaces the review, and"
                    + " keeping the price needs a reason")
    void listPricesAndLaterDeliveries() throws Exception {
        grocerySellsAtFifteenPercent();
        Product sugar = newProduct("SUGAR-" + suffix(), "Sugar 1kg", "STANDARD", "232", true);
        UUID list =
                priceLists
                        .create(
                                "TOWN-" + suffix(),
                                new PriceListService.Definition(
                                        "Town prices", BRANCH, 0, null, null, true))
                        .getId();
        priceLists.setPrice(list, sugar.getId(), new BigDecimal("250"));

        publish(delivery(BRANCH, sugar, "200"));
        eventually(() -> openReviews(sugar) == 1);
        publish(delivery(BRANCH, sugar, "205"));
        eventually(() -> reviews(sugar, "SUPERSEDED") == 1);
        assertThat(openReviews(sugar)).isEqualTo(1);

        String open =
                jdbc.sql(
                                "SELECT id FROM catalog.price_reviews WHERE product_id = ?"
                                        + " AND status = 'OPEN'")
                        .param(sugar.getId())
                        .query(String.class)
                        .single();
        mockMvc.perform(get("/api/v1/price-reviews/" + open).with(MANAGER))
                .andExpect(jsonPath("$.priceSource", is("PRICE_LIST")))
                .andExpect(jsonPath("$.unitCost", is(205.0)));
        mockMvc.perform(
                        post("/api/v1/price-reviews/" + open + "/keep")
                                .with(MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("reason", " "))))
                .andExpect(status().isBadRequest());
        mockMvc.perform(
                        post("/api/v1/price-reviews/" + open + "/accept")
                                .with(MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("price", 285))))
                .andExpect(status().isOk());
        // The list's price moved; the base price did not.
        assertThat(
                        jdbc.sql("SELECT price FROM catalog.price_list_items WHERE product_id = ?")
                                .param(sugar.getId())
                                .query(BigDecimal.class)
                                .single())
                .isEqualByComparingTo("285");
        assertThat(products.findById(sugar.getId()).orElseThrow().getBasePrice())
                .isEqualByComparingTo("232");

        // A delivery at a cost that earns the target opens nothing.
        publish(delivery(BRANCH, sugar, "150"));
        Product marker = newProduct("MARK-" + suffix(), "Marker", "STANDARD", "10", true);
        publish(delivery(BRANCH, marker, "20"));
        eventually(() -> openReviews(marker) == 1);
        assertThat(openReviews(sugar)).isZero();
    }

    @Test
    @DisplayName("keeping a price records the reason and closes the review")
    void keepingAPrice() throws Exception {
        Product salt = newProduct("SALT-" + suffix(), "Salt", "STANDARD", "20", true);
        publish(delivery(BRANCH, salt, "25"));
        eventually(() -> openReviews(salt) == 1);
        String open =
                jdbc.sql("SELECT id FROM catalog.price_reviews WHERE product_id = ?")
                        .param(salt.getId())
                        .query(String.class)
                        .single();

        // No target anywhere, but selling at a loss: break-even, 25 plus VAT, is suggested.
        mockMvc.perform(get("/api/v1/price-reviews/" + open).with(MANAGER))
                .andExpect(jsonPath("$.finding", is("BELOW_COST")))
                .andExpect(jsonPath("$.suggestedPrice", is(29.0)));
        mockMvc.perform(
                        post("/api/v1/price-reviews/" + open + "/keep")
                                .with(MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        EventJson.write(Map.of("reason", "Loss leader this week"))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("KEPT")))
                .andExpect(jsonPath("$.reason", is("Loss leader this week")));
        assertThat(products.findById(salt.getId()).orElseThrow().getBasePrice())
                .isEqualByComparingTo("20");
    }

    private void grocerySellsAtFifteenPercent() throws Exception {
        UUID grocery = categories.findByCode("GROCERY").orElseThrow().getId();
        mockMvc.perform(
                        put("/api/v1/categories/" + grocery + "/target-margin")
                                .with(MANAGER)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(EventJson.write(Map.of("targetMargin", 0.15))))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.targetMargin", is(0.15)));
    }

    private static String suffix() {
        return UUID.randomUUID().toString().substring(0, 8).toUpperCase();
    }

    private long openReviews(Product product) {
        return reviews(product, "OPEN");
    }

    private long reviews(Product product, String status) {
        return jdbc.sql(
                        "SELECT count(*) FROM catalog.price_reviews WHERE product_id = ?"
                                + " AND status = ?")
                .param(product.getId())
                .param(status)
                .query(Long.class)
                .single();
    }

    private static EventEnvelope<GoodsReceivedPayload> delivery(
            UUID branch, Product product, String landedUnitCost) {
        UUID receipt = UUID.randomUUID();
        return EventEnvelope.<GoodsReceivedPayload>builder()
                .topic(Topics.PURCHASING_GOODS_RECEIVED)
                .correlationId("delivery-" + receipt)
                .branchId(branch)
                .payload(
                        new GoodsReceivedPayload(
                                receipt,
                                null,
                                branch,
                                UUID.randomUUID(),
                                UUID.randomUUID(),
                                Instant.now(),
                                List.of(
                                        new GoodsReceivedPayload.ReceivedLine(
                                                product.getId(),
                                                product.getSku(),
                                                new BigDecimal("10"),
                                                "B1",
                                                null,
                                                new BigDecimal(landedUnitCost),
                                                "KES"))))
                .build();
    }

    /** Keyed by branch, as purchasing keys it by receipt: one partition keeps these in order. */
    private void publish(EventEnvelope<GoodsReceivedPayload> envelope) {
        try {
            kafka.send(
                            Topics.PURCHASING_GOODS_RECEIVED,
                            BRANCH.toString(),
                            EventJson.write(envelope))
                    .get(15, TimeUnit.SECONDS);
        } catch (Exception e) {
            throw new IllegalStateException("Could not publish", e);
        }
    }

    private static void eventually(BooleanSupplier condition) throws InterruptedException {
        long deadline = System.currentTimeMillis() + Duration.ofSeconds(30).toMillis();
        while (System.currentTimeMillis() < deadline) {
            if (condition.getAsBoolean()) {
                return;
            }
            Thread.sleep(200);
        }
        throw new AssertionError("Timed out waiting for the listener");
    }
}
