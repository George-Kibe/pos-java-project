package com.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.customer.domain.Customer;
import com.pos.customer.domain.LoyaltyAccount;
import com.pos.customer.domain.LoyaltyTransactionType;
import com.pos.customer.domain.MembershipTier;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.events.sales.SaleVoidedPayload;

/**
 * Earning, keeping and losing points, driven by what the tills did.
 *
 * <p>The roadmap's cases: a member's sale accrues the right points exactly once under redelivery,
 * and a tier upgrade fires at the threshold.
 */
class LoyaltyFlowIT extends CustomerTestBase {

    @Test
    @DisplayName("a member's sale earns points once, however often the event arrives")
    void aSaleEarnsOnceUnderRedelivery() {
        Customer member = enrol("Amina", "0712345678");
        UUID saleId = UUID.randomUUID();
        EventEnvelope<SaleCompletedPayload> sale =
                saleCompleted(member.getId(), saleId, "1052.5377");

        publishAndAwait(Topics.SALES_SALE_COMPLETED, sale, saleId);
        publish(Topics.SALES_SALE_COMPLETED, sale, saleId);
        // Give the redelivery time to be wrong.
        eventually(Duration.ofSeconds(20), "the second delivery", () -> handled(sale.eventId()));

        LoyaltyAccount account = loyalty.require(member.getId());
        // A point per 100 shillings, rounded down: 1052.5377 earns 10.
        assertThat(account.getPointsBalance()).isEqualTo(10);
        assertThat(account.getLifetimePoints()).isEqualTo(10);
        assertThat(loyalty.allFor(member.getId()))
                .filteredOn(t -> t.getType() == LoyaltyTransactionType.ACCRUAL)
                .hasSize(1);
        assertThat(outboxCount(Topics.CUSTOMERS_LOYALTY_ACCRUED)).isEqualTo(1);
        assertThat(latestOutboxPayload(Topics.CUSTOMERS_LOYALTY_ACCRUED))
                .contains("\"points\":10")
                .contains("\"balanceAfter\":10")
                .contains("\"tierCode\":\"BRONZE\"");
    }

    @Test
    @DisplayName("a basket with no member attached earns nobody anything")
    void anAnonymousSaleEarnsNothing() {
        UUID saleId = UUID.randomUUID();

        publishAndAwait(
                Topics.SALES_SALE_COMPLETED, saleCompleted(null, saleId, "5000.00"), saleId);

        assertThat(outboxCount(Topics.CUSTOMERS_LOYALTY_ACCRUED)).isZero();
    }

    @Test
    @DisplayName("crossing the threshold moves the member up, and the multiplier follows")
    void aTierUpgradeFiresAtTheThreshold() {
        Customer member = enrol("Brian", "0712345679");

        // Just under: still bronze.
        spend(member, "49999.00");
        assertThat(tierCodeOf(member)).isEqualTo("BRONZE");
        assertThat(outboxCount(Topics.CUSTOMERS_TIER_CHANGED)).isZero();

        // Over the line: silver, and announced.
        spend(member, "1000.00");
        assertThat(tierCodeOf(member)).isEqualTo("SILVER");
        assertThat(outboxCount(Topics.CUSTOMERS_TIER_CHANGED)).isEqualTo(1);
        assertThat(latestOutboxPayload(Topics.CUSTOMERS_TIER_CHANGED))
                .contains("\"previousTierCode\":\"BRONZE\"")
                .contains("\"tierCode\":\"SILVER\"")
                .contains("\"upgrade\":true");

        long before = loyalty.require(member.getId()).getPointsBalance();
        spend(member, "1000.00");
        // Silver earns 1.25x: 12 points for a thousand shillings, not 10.
        assertThat(loyalty.require(member.getId()).getPointsBalance() - before).isEqualTo(12);
    }

    @Test
    @DisplayName("a refund takes back its share of the points the sale earned")
    void aReturnClawsBackProportionally() {
        Customer member = enrol("Cynthia", "0712345680");
        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), saleId, "1000.00"),
                saleId);
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(10);

        UUID returnId = UUID.randomUUID();
        EventEnvelope<ReturnProcessedPayload> returned =
                returnProcessed(returnId, saleId, "400.00");
        publishAndAwait(Topics.SALES_RETURN_PROCESSED, returned, saleId);
        publish(Topics.SALES_RETURN_PROCESSED, returned, saleId);
        eventually(Duration.ofSeconds(20), "the redelivery", () -> handled(returned.eventId()));

        // Four tenths of the basket came back, so four of its ten points go with it.
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(6);
        assertThat(loyalty.allFor(member.getId()))
                .filteredOn(t -> t.getType() == LoyaltyTransactionType.CLAWBACK)
                .hasSize(1);
        // The refund also reduces standing: the spend that earned it is no longer spend.
        assertThat(loyalty.require(member.getId()).getRollingSpend())
                .isEqualByComparingTo("600.00");
    }

    @Test
    @DisplayName("a voided sale takes back everything it earned")
    void aVoidedSaleLosesItsPoints() {
        Customer member = enrol("Daniel", "0712345681");
        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), saleId, "2000.00"),
                saleId);
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(20);

        EventEnvelope<SaleVoidedPayload> voided = saleVoided(saleId);
        publishAndAwait(Topics.SALES_SALE_VOIDED, voided, saleId);
        publish(Topics.SALES_SALE_VOIDED, voided, saleId);
        eventually(Duration.ofSeconds(20), "the redelivery", () -> handled(voided.eventId()));

        assertThat(loyalty.require(member.getId()).getPointsBalance()).isZero();
        assertThat(loyalty.allFor(member.getId()))
                .filteredOn(t -> t.getType() == LoyaltyTransactionType.CLAWBACK)
                .hasSize(1);
    }

    @Test
    @DisplayName("points a member has already spent are not clawed back into a negative balance")
    void aClawBackStopsAtZero() {
        Customer member = enrol("Esther", "0712345682");
        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), saleId, "1000.00"),
                saleId);
        loyalty.adjust(member.getId(), -8, "Spent on tea");

        publishAndAwait(
                Topics.SALES_RETURN_PROCESSED,
                returnProcessed(UUID.randomUUID(), saleId, "1000.00"),
                saleId);

        assertThat(loyalty.require(member.getId()).getPointsBalance()).isZero();
        assertThat(loyalty.allFor(member.getId()))
                .filteredOn(t -> t.getType() == LoyaltyTransactionType.CLAWBACK)
                .singleElement()
                .satisfies(
                        clawback -> {
                            assertThat(clawback.getPoints()).isEqualTo(-2);
                            assertThat(clawback.getReason()).contains("already spent");
                        });
    }

    @Test
    @DisplayName("points that lapse are written off, oldest first, and only once")
    void lapsedPointsAreWrittenOff() {
        Customer member = enrol("Faith", "0712345683");
        UUID older = UUID.randomUUID();
        UUID newer = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), older, "1000.00"),
                older);
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED, saleCompleted(member.getId(), newer, "500.00"), newer);
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(15);

        // The older lot's year is up.
        jdbc.sql(
                        """
                        UPDATE customer.loyalty_transactions SET expires_at = now() - interval '1 day'
                        WHERE sale_id = :sale AND type = 'ACCRUAL'
                        """)
                .param("sale", older)
                .update();

        assertThat(loyalty.expireLapsed(50)).isEqualTo(1);
        assertThat(loyalty.expireLapsed(50)).isZero();

        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(5);
        assertThat(loyalty.allFor(member.getId()))
                .filteredOn(t -> t.getType() == LoyaltyTransactionType.EXPIRY)
                .singleElement()
                .satisfies(expiry -> assertThat(expiry.getPoints()).isEqualTo(-10));
    }

    @Test
    @DisplayName("a tier falls when the spend behind it ages out of the window")
    void aTierFallsWhenSpendAgesOut() {
        Customer member = enrol("Grace", "0712345684");
        spend(member, "60000.00");
        assertThat(tierCodeOf(member)).isEqualTo("SILVER");

        // That sale is now thirteen months old.
        jdbc.sql(
                        "UPDATE customer.loyalty_transactions SET occurred_at = now() - interval"
                                + " '400 days' WHERE type = 'ACCRUAL'")
                .update();
        jdbc.sql("UPDATE customer.loyalty_accounts SET last_evaluated_at = NULL").update();

        assertThat(loyalty.evaluateStaleTiers(50)).isEqualTo(1);

        assertThat(tierCodeOf(member)).isEqualTo("BRONZE");
        assertThat(latestOutboxPayload(Topics.CUSTOMERS_TIER_CHANGED))
                .contains("\"tierCode\":\"BRONZE\"")
                .contains("\"upgrade\":false");
        // Coming down does not take the points already earned.
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(600);
    }

    // --- helpers ----------------------------------------------------------------

    private void spend(Customer member, String amount) {
        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED, saleCompleted(member.getId(), saleId, amount), saleId);
    }

    private String tierCodeOf(Customer member) {
        return loyalty.tierOf(loyalty.require(member.getId()))
                .map(MembershipTier::getCode)
                .orElse(null);
    }

    private EventEnvelope<ReturnProcessedPayload> returnProcessed(
            UUID returnId, UUID saleId, String refund) {
        return EventEnvelope.<ReturnProcessedPayload>builder()
                .topic(Topics.SALES_RETURN_PROCESSED)
                .branchId(BRANCH)
                .payload(
                        new ReturnProcessedPayload(
                                returnId,
                                saleId,
                                BRANCH,
                                CASHIER,
                                Instant.now(),
                                List.of(),
                                new BigDecimal(refund),
                                "KES",
                                com.pos.events.payments.PaymentMethod.CASH))
                .build();
    }

    private EventEnvelope<SaleVoidedPayload> saleVoided(UUID saleId) {
        return EventEnvelope.<SaleVoidedPayload>builder()
                .topic(Topics.SALES_SALE_VOIDED)
                .branchId(BRANCH)
                .payload(
                        new SaleVoidedPayload(
                                saleId,
                                "R-000001",
                                BRANCH,
                                REGISTER,
                                CASHIER,
                                MANAGER,
                                "WRONG_ITEM",
                                "Rang up the wrong basket",
                                new BigDecimal("2000.00"),
                                "KES",
                                Instant.now()))
                .build();
    }
}
