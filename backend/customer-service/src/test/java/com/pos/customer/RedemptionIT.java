package com.pos.customer;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.customer.domain.Customer;
import com.pos.customer.domain.LoyaltyTransaction;
import com.pos.customer.domain.LoyaltyTransactionType;
import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.events.sales.SaleCancelledPayload;

/**
 * Paying with points.
 *
 * <p>This service answers the tender itself: it holds the points, and the settling runs in a
 * listener with no caller token to borrow. Either answer - authorised or failed - must always be
 * sent, or the lane waits for a sale that can never complete.
 */
class RedemptionIT extends CustomerTestBase {

    @Test
    @DisplayName("points cover the tender, and the sale is told the amount it asked for")
    void pointsSettleATender() {
        Customer member = enrol("Halima", "0712345690");
        loyalty.adjust(member.getId(), 500, "Opening balance");

        UUID saleId = UUID.randomUUID();
        UUID intentId = UUID.randomUUID();
        publishAndAwait(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                loyaltyTender(member.getId(), saleId, intentId, "120.40"),
                saleId);

        // A point is worth a shilling and part points cannot be spent: 121, not 120.
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(379);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
        assertThat(latestOutboxPayload(Topics.PAYMENTS_PAYMENT_AUTHORIZED))
                .contains("\"method\":\"LOYALTY\"")
                // The sale is settled for what it asked for, not for the rounded-up points.
                .contains("\"amountAuthorized\":120.40")
                .contains("\"paymentIntentId\":\"" + intentId + "\"");

        LoyaltyTransaction redemption =
                loyalty.allFor(member.getId()).stream()
                        .filter(t -> t.getType() == LoyaltyTransactionType.REDEMPTION)
                        .findFirst()
                        .orElseThrow();
        assertThat(redemption.getPoints()).isEqualTo(-121);
        assertThat(redemption.getSaleId()).isEqualTo(saleId);
    }

    @Test
    @DisplayName("too few points fails the tender with a reason, and spends nothing")
    void notEnoughPointsFailsTheTender() {
        Customer member = enrol("Ibrahim", "0712345691");
        loyalty.adjust(member.getId(), 50, "Opening balance");

        publishAndAwait(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                loyaltyTender(member.getId(), UUID.randomUUID(), UUID.randomUUID(), "200.00"),
                UUID.randomUUID());

        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(50);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isZero();
        assertThat(latestOutboxPayload(Topics.PAYMENTS_PAYMENT_FAILED))
                .contains("\"reasonCode\":\"INSUFFICIENT_POINTS\"")
                .contains("50 points");
    }

    @Test
    @DisplayName("a tender on a basket with no member is refused, not left hanging")
    void anAnonymousBasketCannotPayWithPoints() {
        publishAndAwait(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                loyaltyTender(null, UUID.randomUUID(), UUID.randomUUID(), "50.00"),
                UUID.randomUUID());

        assertThat(latestOutboxPayload(Topics.PAYMENTS_PAYMENT_FAILED))
                .contains("\"reasonCode\":\"NO_CUSTOMER\"");
    }

    @Test
    @DisplayName("a customer with no account is refused rather than failing the listener")
    void aCustomerWithoutAnAccountIsRefused() {
        publishAndAwait(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                loyaltyTender(UUID.randomUUID(), UUID.randomUUID(), UUID.randomUUID(), "50.00"),
                UUID.randomUUID());

        assertThat(latestOutboxPayload(Topics.PAYMENTS_PAYMENT_FAILED))
                .contains("\"reasonCode\":\"NO_LOYALTY_ACCOUNT\"");
    }

    @Test
    @DisplayName("a redelivered tender spends the points once and answers once")
    void aRedeliveredTenderSpendsOnce() {
        Customer member = enrol("Joyce", "0712345692");
        loyalty.adjust(member.getId(), 500, "Opening balance");
        EventEnvelope<PaymentRequestedPayload> tender =
                loyaltyTender(member.getId(), UUID.randomUUID(), UUID.randomUUID(), "100.00");

        publishAndAwait(Topics.PAYMENTS_PAYMENT_REQUESTED, tender, UUID.randomUUID());
        publish(Topics.PAYMENTS_PAYMENT_REQUESTED, tender, UUID.randomUUID());
        eventually(Duration.ofSeconds(20), "the redelivery", () -> handled(tender.eventId()));

        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(400);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a cancelled sale gives the points back, keeping their original expiry")
    void aCancelledSaleReturnsThePoints() {
        Customer member = enrol("Kevin", "0712345693");
        UUID earningSale = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), earningSale, "10000.00"),
                earningSale);
        Instant originalExpiry =
                loyalty.allFor(member.getId()).stream()
                        .filter(t -> t.getType() == LoyaltyTransactionType.ACCRUAL)
                        .findFirst()
                        .orElseThrow()
                        .getExpiresAt();

        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                loyaltyTender(member.getId(), saleId, UUID.randomUUID(), "50.00"),
                saleId);
        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(50);

        EventEnvelope<SaleCancelledPayload> cancelled = saleCancelled(saleId);
        publishAndAwait(Topics.SALES_SALE_CANCELLED, cancelled, saleId);
        publish(Topics.SALES_SALE_CANCELLED, cancelled, saleId);
        eventually(Duration.ofSeconds(20), "the redelivery", () -> handled(cancelled.eventId()));

        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(100);
        assertThat(loyalty.allFor(member.getId()))
                .filteredOn(t -> t.getType() == LoyaltyTransactionType.REVERSAL)
                .singleElement()
                .satisfies(reversal -> assertThat(reversal.getPoints()).isEqualTo(50));

        // The points went back into the lot they came out of, with the expiry it already had:
        // the member is left exactly as they were, not given a fresh year.
        assertThat(loyalty.allFor(member.getId()))
                .filteredOn(t -> t.getType() == LoyaltyTransactionType.ACCRUAL)
                .singleElement()
                .satisfies(
                        lot -> {
                            assertThat(lot.getPointsRemaining()).isEqualTo(100);
                            assertThat(lot.getExpiresAt()).isEqualTo(originalExpiry);
                        });
    }

    @Test
    @DisplayName("the points about to lapse are the ones spent first")
    void spendingTakesTheSoonestToExpireFirst() {
        Customer member = enrol("Lucy", "0712345694");
        UUID oldSale = UUID.randomUUID();
        UUID newSale = UUID.randomUUID();
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), oldSale, "5000.00"),
                oldSale);
        publishAndAwait(
                Topics.SALES_SALE_COMPLETED,
                saleCompleted(member.getId(), newSale, "5000.00"),
                newSale);
        // The first lot is nearly up.
        jdbc.sql(
                        """
                        UPDATE customer.loyalty_transactions SET expires_at = now() + interval '2 days'
                        WHERE sale_id = :sale AND type = 'ACCRUAL'
                        """)
                .param("sale", oldSale)
                .update();

        UUID saleId = UUID.randomUUID();
        publishAndAwait(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                loyaltyTender(member.getId(), saleId, UUID.randomUUID(), "30.00"),
                saleId);

        assertThat(remainingOn(oldSale)).isEqualTo(20);
        assertThat(remainingOn(newSale)).isEqualTo(50);
    }

    @Test
    void anotherServicesTenderIsIgnored() {
        Customer member = enrol("Mary", "0712345695");
        loyalty.adjust(member.getId(), 500, "Opening balance");
        EventEnvelope<PaymentRequestedPayload> cardTender =
                EventEnvelope.<PaymentRequestedPayload>builder()
                        .topic(Topics.PAYMENTS_PAYMENT_REQUESTED)
                        .branchId(BRANCH)
                        .payload(
                                new PaymentRequestedPayload(
                                        UUID.randomUUID(),
                                        UUID.randomUUID(),
                                        "R-000003",
                                        BRANCH,
                                        REGISTER,
                                        CASHIER,
                                        PaymentMethod.CARD,
                                        new BigDecimal("100.00"),
                                        "KES",
                                        null,
                                        "TERM-1",
                                        Instant.now(),
                                        member.getId()))
                        .build();

        publish(Topics.PAYMENTS_PAYMENT_REQUESTED, cardTender, UUID.randomUUID());
        // Nothing to wait for, so wait for something that would come after it.
        publishAndAwait(
                Topics.PAYMENTS_PAYMENT_REQUESTED,
                loyaltyTender(member.getId(), UUID.randomUUID(), UUID.randomUUID(), "10.00"),
                UUID.randomUUID());

        assertThat(loyalty.require(member.getId()).getPointsBalance()).isEqualTo(490);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    // --- helpers ----------------------------------------------------------------

    private long remainingOn(UUID saleId) {
        Long remaining =
                jdbc.sql(
                                """
                                SELECT points_remaining FROM customer.loyalty_transactions
                                WHERE sale_id = :sale AND type = 'ACCRUAL'
                                """)
                        .param("sale", saleId)
                        .query(Long.class)
                        .single();
        return remaining == null ? 0 : remaining;
    }

    private EventEnvelope<SaleCancelledPayload> saleCancelled(UUID saleId) {
        return EventEnvelope.<SaleCancelledPayload>builder()
                .topic(Topics.SALES_SALE_CANCELLED)
                .branchId(BRANCH)
                .payload(
                        new SaleCancelledPayload(
                                saleId,
                                BRANCH,
                                REGISTER,
                                CASHIER,
                                UUID.randomUUID(),
                                "Payment failed: CANCELLED_BY_USER",
                                Instant.now()))
                .build();
    }
}
