package com.pos.events;

import static org.assertj.core.api.Assertions.assertThat;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Nested;
import org.junit.jupiter.api.Test;

import com.pos.events.inventory.AdjustmentPostedPayload;
import com.pos.events.inventory.BatchExpiringPayload;
import com.pos.events.inventory.LowStockPayload;
import com.pos.events.inventory.NegativeStockDetectedPayload;
import com.pos.events.inventory.StockDeductedPayload;
import com.pos.events.payments.PaymentAuthorizedPayload;
import com.pos.events.payments.PaymentFailedPayload;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRefundedPayload;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.events.purchasing.GoodsReceivedPayload;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.events.sales.SaleCancelledPayload;
import com.pos.events.sales.SaleCompletedPayload;
import com.pos.events.sales.SaleVoidedPayload;
import com.pos.events.sales.ShiftClosedPayload;

/**
 * The published shape of every payload.
 *
 * <p>These records are a wire contract between services that deploy separately, and schema
 * evolution is additive only. A field renamed or dropped here silently stops reaching consumers -
 * the JSON still parses, the field just arrives null - so the names are asserted rather than left
 * to whatever the compiler happens to emit.
 *
 * <p>Quantities and money are also checked to survive the round trip as {@link BigDecimal} with
 * their scale intact, because a quantity that comes back as a double has already lost the thing
 * that made it worth carrying.
 */
class PayloadContractTest {

    private static final UUID ID = UUID.fromString("018f3a1c-0000-7000-8000-000000000001");

    @Nested
    class Sales {

        @Test
        @DisplayName("a completed sale keeps every field a consumer reads")
        void saleCompleted() {
            SaleCompletedPayload payload =
                    new SaleCompletedPayload(
                            ID,
                            "R-000123",
                            ID,
                            ID,
                            ID,
                            ID,
                            ID,
                            Instant.parse("2026-01-02T03:04:05Z"),
                            List.of(
                                    new SaleCompletedPayload.SaleLine(
                                            ID,
                                            "SKU-1",
                                            "Maize flour 2kg",
                                            new BigDecimal("1.500"),
                                            new BigDecimal("120.0000"),
                                            new BigDecimal("180.0000"),
                                            new BigDecimal("24.8276"),
                                            "VAT16",
                                            "B-1")),
                            new BigDecimal("155.1724"),
                            new BigDecimal("24.8276"),
                            new BigDecimal("180.0000"),
                            "KES",
                            ID);

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"saleId\"")
                    .contains("\"cartId\"")
                    .contains("\"receiptNumber\"")
                    .contains("\"registerId\"")
                    .contains("\"shiftId\"")
                    .contains("\"cashierId\"")
                    .contains("\"customerId\"")
                    .contains("\"completedAt\"")
                    .contains("\"netTotal\"")
                    .contains("\"taxTotal\"")
                    .contains("\"grandTotal\"")
                    .contains("\"currency\"")
                    .contains("\"taxClassCode\"")
                    .contains("\"lineTotal\"");

            SaleCompletedPayload back = EventJson.read(json, SaleCompletedPayload.class);
            assertThat(back).isEqualTo(payload);
            // Scale carries the meaning: 1.500 kg is a weighed quantity, 1.5 is a guess at one.
            assertThat(back.lines().getFirst().quantity()).isEqualTo(new BigDecimal("1.500"));
            assertThat(back.grandTotal()).isEqualTo(new BigDecimal("180.0000"));
        }

        @Test
        void aReturnCarriesWhetherTheGoodsCanBeSoldAgain() {
            ReturnProcessedPayload payload =
                    new ReturnProcessedPayload(
                            ID,
                            ID,
                            ID,
                            ID,
                            Instant.parse("2026-01-02T03:04:05Z"),
                            List.of(
                                    new ReturnProcessedPayload.ReturnLine(
                                            ID,
                                            "SKU-1",
                                            new BigDecimal("1.000"),
                                            false,
                                            "B-1",
                                            "DAMAGED")),
                            new BigDecimal("180.0000"),
                            "KES",
                            PaymentMethod.MPESA);

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"refundMethod\":\"MPESA\"")
                    .contains("\"returnId\"")
                    .contains("\"originalSaleId\"")
                    .contains("\"resaleable\"")
                    .contains("\"refundTotal\"");

            ReturnProcessedPayload back = EventJson.read(json, ReturnProcessedPayload.class);
            assertThat(back).isEqualTo(payload);
            // Inventory restocks on this flag alone; defaulting it to true resells broken goods.
            assertThat(back.lines().getFirst().resaleable()).isFalse();
        }

        @Test
        void aVoidNamesTheSupervisorWhoApprovedIt() {
            SaleVoidedPayload payload =
                    new SaleVoidedPayload(
                            ID,
                            "R-000123",
                            ID,
                            ID,
                            ID,
                            ID,
                            "WRONG_ITEM",
                            "Customer changed their mind",
                            new BigDecimal("180.0000"),
                            "KES",
                            Instant.parse("2026-01-02T03:04:05Z"));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"saleId\"")
                    .contains("\"approvedBy\"")
                    .contains("\"reasonCode\"")
                    .contains("\"voidedAt\"");

            assertThat(EventJson.read(json, SaleVoidedPayload.class)).isEqualTo(payload);
        }

        @Test
        void aCancellationNamesTheCartWhoseHoldsToRelease() {
            SaleCancelledPayload payload =
                    new SaleCancelledPayload(
                            ID,
                            ID,
                            ID,
                            ID,
                            ID,
                            "Payment failed: CANCELLED_BY_USER",
                            Instant.parse("2026-01-02T03:04:05Z"));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"saleId\"")
                    .contains("\"cartId\"")
                    .contains("\"reason\"")
                    .contains("\"cancelledAt\"");

            assertThat(EventJson.read(json, SaleCancelledPayload.class)).isEqualTo(payload);
        }

        @Test
        void aClosedShiftCarriesItsVarianceWithTheSignIntact() {
            ShiftClosedPayload payload =
                    new ShiftClosedPayload(
                            ID,
                            ID,
                            ID,
                            ID,
                            ID,
                            Instant.parse("2026-01-02T03:04:05Z"),
                            Instant.parse("2026-01-02T11:04:05Z"),
                            new BigDecimal("5000.0000"),
                            new BigDecimal("12000.0000"),
                            new BigDecimal("300.0000"),
                            new BigDecimal("10000.0000"),
                            new BigDecimal("6700.0000"),
                            new BigDecimal("6650.0000"),
                            new BigDecimal("-50.0000"),
                            new BigDecimal("8000.0000"),
                            42,
                            "KES");

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"tillSessionId\"")
                    .contains("\"expectedCash\"")
                    .contains("\"countedCash\"")
                    .contains("\"variance\"")
                    .contains("\"saleCount\":42");

            ShiftClosedPayload back = EventJson.read(json, ShiftClosedPayload.class);
            assertThat(back).isEqualTo(payload);
            assertThat(back.variance()).isEqualTo(new BigDecimal("-50.0000"));
            assertThat(back.isShort()).isTrue();
        }
    }

    @Nested
    class Payments {

        @Test
        void aPaymentRequestCarriesTheMethodAsItsName() {
            PaymentRequestedPayload payload =
                    new PaymentRequestedPayload(
                            ID,
                            ID,
                            "R-000123",
                            ID,
                            ID,
                            ID,
                            PaymentMethod.MPESA,
                            new BigDecimal("180.0000"),
                            "KES",
                            "254700000000",
                            null,
                            Instant.parse("2026-01-02T03:04:05Z"));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"paymentIntentId\"")
                    .contains("\"saleId\"")
                    .contains("\"method\":\"MPESA\"")
                    .contains("\"phoneNumber\"")
                    .contains("\"requestedAt\"");

            assertThat(EventJson.read(json, PaymentRequestedPayload.class)).isEqualTo(payload);
        }

        @Test
        void anAuthorisationCarriesTheReferenceADisputeIsSettledWith() {
            PaymentAuthorizedPayload payload =
                    new PaymentAuthorizedPayload(
                            ID,
                            ID,
                            ID,
                            PaymentMethod.CARD,
                            new BigDecimal("180.0000"),
                            "KES",
                            "TERM-7-000812",
                            "A1B2C3",
                            Instant.parse("2026-01-02T03:04:05Z"));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"amountAuthorized\"")
                    .contains("\"providerReference\"")
                    .contains("\"approvalCode\"")
                    .contains("\"authorizedAt\"");

            PaymentAuthorizedPayload back = EventJson.read(json, PaymentAuthorizedPayload.class);
            assertThat(back).isEqualTo(payload);
            assertThat(back.amountAuthorized()).isEqualTo(new BigDecimal("180.0000"));
        }

        @Test
        void aRefundCarriesTheProviderReferenceItWasConfirmedBy() {
            PaymentRefundedPayload payload =
                    new PaymentRefundedPayload(
                            ID,
                            ID,
                            ID,
                            ID,
                            ID,
                            PaymentMethod.MPESA,
                            new BigDecimal("180.0000"),
                            "KES",
                            "QKR12XYZ9",
                            Instant.parse("2026-01-02T03:04:05Z"));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"refundId\"")
                    .contains("\"returnId\"")
                    .contains("\"providerReference\":\"QKR12XYZ9\"")
                    .contains("\"refundedAt\"");

            assertThat(EventJson.read(json, PaymentRefundedPayload.class)).isEqualTo(payload);
        }

        @Test
        void aFailureCarriesAStableReasonCode() {
            PaymentFailedPayload payload =
                    new PaymentFailedPayload(
                            ID,
                            ID,
                            ID,
                            PaymentMethod.MPESA,
                            new BigDecimal("180.0000"),
                            "KES",
                            "INSUFFICIENT_FUNDS",
                            "The balance is insufficient for the transaction.",
                            Instant.parse("2026-01-02T03:04:05Z"));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"reasonCode\":\"INSUFFICIENT_FUNDS\"")
                    .contains("\"providerMessage\"")
                    .contains("\"failedAt\"");

            assertThat(EventJson.read(json, PaymentFailedPayload.class)).isEqualTo(payload);
        }
    }

    @Nested
    class Purchasing {

        @Test
        void aDeliveryCarriesBatchExpiryAsACalendarDay() {
            GoodsReceivedPayload payload =
                    new GoodsReceivedPayload(
                            ID,
                            ID,
                            ID,
                            ID,
                            ID,
                            Instant.parse("2026-01-02T03:04:05Z"),
                            List.of(
                                    new GoodsReceivedPayload.ReceivedLine(
                                            ID,
                                            "SKU-1",
                                            new BigDecimal("24.000"),
                                            "B-1",
                                            LocalDate.of(2026, 5, 12),
                                            new BigDecimal("95.5000"),
                                            "KES")));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"goodsReceivedNoteId\"")
                    .contains("\"purchaseOrderId\"")
                    .contains("\"supplierId\"")
                    .contains("\"unitCost\"")
                    // A date, not a timestamp: "best before 12 May" has no time zone.
                    .contains("\"expiryDate\":\"2026-05-12\"");

            assertThat(EventJson.read(json, GoodsReceivedPayload.class)).isEqualTo(payload);
        }
    }

    @Nested
    class Inventory {

        @Test
        @DisplayName("a deduction says which batch each unit came out of")
        void stockDeducted() {
            StockDeductedPayload payload =
                    new StockDeductedPayload(
                            ID,
                            ID,
                            Instant.parse("2026-01-02T03:04:05Z"),
                            List.of(
                                    new StockDeductedPayload.DeductedLine(
                                            ID,
                                            new BigDecimal("3.000"),
                                            new BigDecimal("17.000"),
                                            List.of(
                                                    new StockDeductedPayload.BatchAllocation(
                                                            ID,
                                                            "B-1",
                                                            new BigDecimal("2.000"),
                                                            new BigDecimal("95.5000")),
                                                    new StockDeductedPayload.BatchAllocation(
                                                            ID,
                                                            "B-2",
                                                            new BigDecimal("1.000"),
                                                            new BigDecimal("97.0000"))))));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"deductedAt\"")
                    .contains("\"quantityOnHandAfter\"")
                    .contains("\"allocations\"")
                    .contains("\"batchNumber\"")
                    .contains("\"unitCost\"");

            StockDeductedPayload back = EventJson.read(json, StockDeductedPayload.class);
            assertThat(back).isEqualTo(payload);
            // Two batches for one line: cost of sale is the sum, not one unit cost times quantity.
            assertThat(back.lines().getFirst().allocations()).hasSize(2);
        }

        @Test
        void anAdjustmentCarriesASignedDeltaAndItsReason() {
            AdjustmentPostedPayload payload =
                    new AdjustmentPostedPayload(
                            ID,
                            ID,
                            "DAMAGE",
                            ID,
                            Instant.parse("2026-01-02T03:04:05Z"),
                            "Dropped a crate",
                            List.of(
                                    new AdjustmentPostedPayload.AdjustmentLine(
                                            ID,
                                            "SKU-1",
                                            new BigDecimal("-4.000"),
                                            "B-1",
                                            new BigDecimal("382.0000"),
                                            "KES")));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"adjustmentId\"")
                    .contains("\"reasonCode\"")
                    .contains("\"postedBy\"")
                    .contains("\"quantityDelta\"")
                    .contains("\"valueAtCost\"");

            AdjustmentPostedPayload back = EventJson.read(json, AdjustmentPostedPayload.class);
            assertThat(back).isEqualTo(payload);
            // The sign is the whole meaning; an absolute value would post a write-off as a receipt.
            assertThat(back.lines().getFirst().quantityDelta()).isNegative();
        }

        @Test
        void anExpiringBatchCarriesWhatItIsWorthAndHowLongItHas() {
            BatchExpiringPayload payload =
                    new BatchExpiringPayload(
                            ID,
                            "B-1",
                            ID,
                            "SKU-1",
                            "Milk 500ml",
                            ID,
                            LocalDate.of(2026, 5, 12),
                            7L,
                            new BigDecimal("18.000"),
                            new BigDecimal("1719.0000"),
                            "KES");

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"daysUntilExpiry\":7")
                    .contains("\"quantityRemaining\"")
                    .contains("\"valueAtCost\"")
                    .contains("\"expiryDate\":\"2026-05-12\"");

            assertThat(EventJson.read(json, BatchExpiringPayload.class)).isEqualTo(payload);
        }

        @Test
        void lowStockCarriesEnoughToRaiseAPurchaseOrder() {
            LowStockPayload payload =
                    new LowStockPayload(
                            ID,
                            "SKU-1",
                            "Maize flour 2kg",
                            ID,
                            new BigDecimal("3.000"),
                            new BigDecimal("5.000"),
                            new BigDecimal("24.000"));

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"quantityOnHand\"")
                    .contains("\"reorderPoint\"")
                    .contains("\"suggestedOrderQuantity\"");

            assertThat(EventJson.read(json, LowStockPayload.class)).isEqualTo(payload);
        }

        @Test
        @DisplayName("negative stock says what was attempted, not only what is left")
        void negativeStockDetected() {
            NegativeStockDetectedPayload payload =
                    new NegativeStockDetectedPayload(
                            ID,
                            "SKU-1",
                            ID,
                            new BigDecimal("-2.000"),
                            new BigDecimal("5.000"),
                            "SALE",
                            ID);

            String json = EventJson.write(payload);
            assertThat(json)
                    .contains("\"attemptedDeduction\"")
                    .contains("\"triggeredBy\"")
                    .contains("\"referenceId\"");

            NegativeStockDetectedPayload back =
                    EventJson.read(json, NegativeStockDetectedPayload.class);
            assertThat(back).isEqualTo(payload);
            assertThat(back.quantityOnHand()).isNegative();
        }
    }

    @Test
    @DisplayName("a payload survives the envelope it travels in")
    void aPayloadRoundTripsInsideItsEnvelope() {
        LowStockPayload payload =
                new LowStockPayload(
                        ID,
                        "SKU-1",
                        "Maize flour 2kg",
                        ID,
                        new BigDecimal("3.000"),
                        new BigDecimal("5.000"),
                        new BigDecimal("24.000"));

        EventEnvelope<LowStockPayload> envelope =
                EventEnvelope.<LowStockPayload>builder()
                        .topic(Topics.INVENTORY_LOW_STOCK)
                        .branchId(ID)
                        .payload(payload)
                        .build();

        EventEnvelope<LowStockPayload> back =
                EventJson.readEnvelope(EventJson.write(envelope), LowStockPayload.class);

        assertThat(back.payload()).isEqualTo(payload);
        assertThat(back.eventType()).isEqualTo(envelope.eventType());
        assertThat(back.eventId()).isEqualTo(envelope.eventId());
    }
}
