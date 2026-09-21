package com.pos.purchasing;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.catalog.ProductChangedPayload;
import com.pos.events.inventory.LowStockPayload;
import com.pos.purchasing.domain.ReorderSuggestion;
import com.pos.purchasing.domain.SuggestionStatus;
import com.pos.purchasing.domain.Supplier;
import com.pos.purchasing.domain.SupplierProduct;
import com.pos.purchasing.repository.ReorderSuggestionRepository;
import com.pos.purchasing.repository.SupplierProductRepository;
import com.pos.purchasing.service.SupplierService;

/**
 * What purchasing does when other services speak.
 *
 * <p>Published onto real topics, so the listener's deserialisation, idempotency marker and
 * transaction boundary are all in play. The redelivery cases matter most: Kafka guarantees at least
 * once, and a shortage that turns into two suggestions is a shortage ordered twice.
 */
class PurchasingEventsIT extends PurchasingTestBase {

    private static final UUID BRANCH = UUID.randomUUID();

    @Autowired private ReorderSuggestionRepository suggestions;
    @Autowired private SupplierProductRepository supplierProducts;
    @Autowired private SupplierService supplierService;

    @Test
    @DisplayName("a low-stock event raises a reorder suggestion from the preferred supplier")
    void lowStockRaisesASuggestion() {
        UUID product = UUID.randomUUID();
        givenPreferredSupplier(product, "SUGAR-1KG", "42.00", "24");

        publish(
                Topics.INVENTORY_LOW_STOCK,
                lowStock(product, "SUGAR-1KG", "3.000", "5.000", "24.000"),
                product);

        eventually(
                Duration.ofSeconds(30), "the suggestion to appear", () -> suggestionCount() == 1);

        ReorderSuggestion suggestion =
                suggestions
                        .findByProductIdAndBranchIdAndStatus(product, BRANCH, SuggestionStatus.OPEN)
                        .orElseThrow();

        assertThat(suggestion.getSku()).isEqualTo("SUGAR-1KG");
        assertThat(suggestion.getQuantityOnHand()).isEqualByComparingTo("3.000");
        assertThat(suggestion.getSuggestedQuantity()).isEqualByComparingTo("24.000");
        // Priced from the supplier's price list, so a buyer sees what the order would cost.
        assertThat(suggestion.getUnitCost()).isEqualByComparingTo("42.00");
        assertThat(suggestion.estimatedValue()).isEqualByComparingTo("1008.00");
        assertThat(suggestion.getSupplier()).isNotNull();
    }

    @Test
    @DisplayName("a redelivered low-stock event does not produce a second suggestion")
    void aRedeliveredEventChangesNothing() {
        UUID product = UUID.randomUUID();
        givenPreferredSupplier(product, "FLOUR-2KG", "95.00", "10");

        EventEnvelope<LowStockPayload> event =
                lowStock(product, "FLOUR-2KG", "2.000", "5.000", "20.000");

        publish(Topics.INVENTORY_LOW_STOCK, event, product);
        eventually(Duration.ofSeconds(30), "the first suggestion", () -> suggestionCount() == 1);

        // The same event id again, which is exactly what a rebalancing consumer group sends.
        publish(Topics.INVENTORY_LOW_STOCK, event, product);

        eventually(
                Duration.ofSeconds(10),
                "the redelivery to be recorded as already handled",
                () -> processedCount(event.eventId()) == 1);
        assertThat(suggestionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName(
            "a shortage that keeps reporting sharpens one suggestion rather than piling up rows")
    void repeatedShortagesUpdateOneSuggestion() {
        UUID product = UUID.randomUUID();
        givenPreferredSupplier(product, "MILK-500ML", "55.00", "12");

        publish(
                Topics.INVENTORY_LOW_STOCK,
                lowStock(product, "MILK-500ML", "4.000", "5.000", "12.000"),
                product);
        eventually(Duration.ofSeconds(30), "the first suggestion", () -> suggestionCount() == 1);

        // A different event - stock fell further - so it is not a redelivery.
        publish(
                Topics.INVENTORY_LOW_STOCK,
                lowStock(product, "MILK-500ML", "1.000", "5.000", "12.000"),
                product);

        eventually(
                Duration.ofSeconds(30),
                "the suggestion to be updated with the lower figure",
                () ->
                        suggestions
                                .findByProductIdAndBranchIdAndStatus(
                                        product, BRANCH, SuggestionStatus.OPEN)
                                .filter(s -> s.getQuantityOnHand().compareTo(BigDecimal.ONE) == 0)
                                .isPresent());

        // Still one row: a list that grows on every tick is a list nobody reads.
        assertThat(suggestionCount()).isEqualTo(1);
    }

    @Test
    @DisplayName("a suggestion is raised even with no supplier set up for the product")
    void aShortageWithNoSupplierStillWarns() {
        UUID product = UUID.randomUUID();

        publish(
                Topics.INVENTORY_LOW_STOCK,
                lowStock(product, "ORPHAN-1", "0.000", "5.000", "10.000"),
                product);

        eventually(
                Duration.ofSeconds(30), "the suggestion to appear", () -> suggestionCount() == 1);

        ReorderSuggestion suggestion =
                suggestions
                        .findByProductIdAndBranchIdAndStatus(product, BRANCH, SuggestionStatus.OPEN)
                        .orElseThrow();
        assertThat(suggestion.getSupplier()).isNull();
        assertThat(suggestion.getSuggestedQuantity()).isEqualByComparingTo("10.000");
        // No price list to price it from, so no estimate is invented.
        assertThat(suggestion.estimatedValue()).isNull();
    }

    @Test
    @DisplayName("a suggestion never asks for less than the supplier will ship")
    void theMinimumOrderQuantityWins() {
        UUID product = UUID.randomUUID();
        // The supplier sells in cases of 24.
        givenPreferredSupplier(product, "CASE-24", "20.00", "24");

        publish(
                Topics.INVENTORY_LOW_STOCK,
                lowStock(product, "CASE-24", "2.000", "5.000", "6.000"),
                product);

        eventually(
                Duration.ofSeconds(30), "the suggestion to appear", () -> suggestionCount() == 1);

        ReorderSuggestion suggestion =
                suggestions
                        .findByProductIdAndBranchIdAndStatus(product, BRANCH, SuggestionStatus.OPEN)
                        .orElseThrow();
        // Inventory asked for 6; ordering 6 of a 24-case product gets the order rejected.
        assertThat(suggestion.getSuggestedQuantity()).isEqualByComparingTo("24.000");
    }

    @Test
    @DisplayName("a renamed product updates the supplier price lists that reference it")
    void aProductChangeRefreshesThePriceList() {
        UUID product = UUID.randomUUID();
        givenPreferredSupplier(product, "OLD-SKU", "10.00", "1");

        publish(
                Topics.CATALOG_PRODUCT_CHANGED,
                EventEnvelope.<ProductChangedPayload>builder()
                        .topic(Topics.CATALOG_PRODUCT_CHANGED)
                        .payload(
                                new ProductChangedPayload(
                                        product,
                                        "NEW-SKU",
                                        "Renamed Product",
                                        null,
                                        null,
                                        "EACH",
                                        "VAT16",
                                        false,
                                        true,
                                        new BigDecimal("12.00"),
                                        "KES"))
                        .build(),
                product);

        eventually(
                Duration.ofSeconds(30),
                "the cached SKU to be refreshed",
                () ->
                        supplierProducts.findByProductId(product).stream()
                                .anyMatch(p -> "NEW-SKU".equals(p.getSku())));

        SupplierProduct refreshed = supplierProducts.findByProductId(product).getFirst();
        assertThat(refreshed.getProductName()).isEqualTo("Renamed Product");
    }

    // --- helpers ----------------------------------------------------------------

    private void givenPreferredSupplier(
            UUID productId, String sku, String cost, String minimumOrderQty) {

        Supplier supplier =
                givenSupplier(
                        "SUP-" + UUID.randomUUID().toString().substring(0, 8), "Preferred Ltd");
        supplierService.addProduct(
                supplier.getId(),
                productId,
                sku,
                sku,
                new BigDecimal(cost),
                null,
                new BigDecimal(minimumOrderQty),
                null,
                true);
    }

    private static EventEnvelope<LowStockPayload> lowStock(
            UUID productId,
            String sku,
            String onHand,
            String reorderPoint,
            String suggestedQuantity) {

        return EventEnvelope.<LowStockPayload>builder()
                .topic(Topics.INVENTORY_LOW_STOCK)
                .branchId(BRANCH)
                .payload(
                        new LowStockPayload(
                                productId,
                                sku,
                                sku,
                                BRANCH,
                                new BigDecimal(onHand),
                                new BigDecimal(reorderPoint),
                                new BigDecimal(suggestedQuantity)))
                .build();
    }

    private long processedCount(String eventId) {
        Long count =
                jdbc.sql(
                                """
                                SELECT count(*) FROM purchasing.processed_event
                                WHERE event_id = :id
                                """)
                        .param("id", eventId)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }
}
