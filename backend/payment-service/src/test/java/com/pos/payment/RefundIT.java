package com.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.time.Duration;
import java.time.Instant;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;
import org.springframework.test.web.servlet.ResultActions;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.sales.ReturnProcessedPayload;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.domain.Refund;
import com.pos.payment.domain.RefundStatus;
import com.pos.payment.repository.RefundRepository;
import com.pos.payment.service.MpesaStatusSweeper;
import com.pos.payment.service.RefundDispatcher;

/**
 * Money going back the way it came, from a return processed at the till.
 *
 * <p>M-Pesa's constraint drives most of it: a whole transaction can be reversed, part of one
 * cannot. Everything the provider cannot do is raised for a person, with the reason.
 */
class RefundIT extends PaymentTestBase {

    @Autowired private RefundDispatcher refundDispatcher;
    @Autowired private RefundRepository refunds;
    @Autowired private MpesaStatusSweeper sweeper;

    @Test
    @DisplayName("a full M-Pesa refund is reversed, and announced once Daraja confirms it")
    void aFullMpesaRefundIsReversed() throws Exception {
        PaymentIntent paid = paidByMpesa("1052.5377", "QKR12XYZ9");

        List<Refund> planned = returned(paid.getSaleId(), "1052.5377", PaymentMethod.MPESA);
        assertThat(planned)
                .singleElement()
                .extracting(Refund::getStatus)
                .isEqualTo(RefundStatus.PENDING_DISPATCH);

        refundDispatcher.dispatchDue();

        Map<String, Object> reversal = DARAJA.reversalRequests().getFirst();
        assertThat(reversal.get("TransactionID")).isEqualTo("QKR12XYZ9");
        // The whole shillings the customer was charged, not the four-decimal sale amount.
        assertThat(reversal.get("Amount")).isEqualTo(1053);
        assertThat(reversal.get("CommandID")).isEqualTo("TransactionReversal");
        assertThat(String.valueOf(reversal.get("ResultURL")))
                .endsWith("/callbacks/reversal/" + CALLBACK_TOKEN);
        assertThat(refunds.findById(planned.getFirst().getId()).orElseThrow().getStatus())
                .isEqualTo(RefundStatus.PROCESSING);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_REFUNDED)).isZero();

        reversalResult("oc-1", 0, "QKV77REV1").andExpect(status().isOk());
        reversalResult("oc-1", 0, "QKV77REV1").andExpect(status().isOk());

        Refund done = refunds.findById(planned.getFirst().getId()).orElseThrow();
        assertThat(done.getStatus()).isEqualTo(RefundStatus.COMPLETED);
        assertThat(done.getProviderReference()).isEqualTo("QKV77REV1");
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_REFUNDED)).isEqualTo(1);
        assertThat(latestOutboxPayload(Topics.PAYMENTS_PAYMENT_REFUNDED))
                .contains("\"providerReference\":\"QKV77REV1\"")
                .contains("\"method\":\"MPESA\"");
    }

    @Test
    @DisplayName(
            "part of an M-Pesa payment cannot be reversed, so a person settles it and says how")
    void aPartialMpesaRefundNeedsAPerson() throws Exception {
        PaymentIntent paid = paidByMpesa("232.00", "QKP1");

        Refund planned = returned(paid.getSaleId(), "116.00", PaymentMethod.MPESA).getFirst();
        assertThat(planned.getStatus()).isEqualTo(RefundStatus.REQUIRES_ACTION);
        assertThat(planned.getActionReason()).isEqualTo("MPESA_PARTIAL_REFUND");

        refundDispatcher.dispatchDue();
        assertThat(DARAJA.reversalRequests()).isEmpty();

        mockMvc.perform(
                        get("/api/v1/refunds")
                                .param("branchId", BRANCH.toString())
                                .param("status", "REQUIRES_ACTION")
                                .with(supervisor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));

        mockMvc.perform(
                        post("/api/v1/refunds/" + planned.getId() + "/settle")
                                .with(supervisor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"via\":\"CASH\",\"reference\":\"SAFE-12\","
                                                + "\"note\":\"Paid from the safe, customer signed\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")))
                .andExpect(jsonPath("$.settledVia", is("CASH")))
                .andExpect(jsonPath("$.settledBy", is(SUPERVISOR.toString())));

        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_REFUNDED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a payment recovered by query has no receipt yet, so it cannot be reversed yet")
    void aRefundOfAPaymentWithoutAReceiptWaits() {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));
        DARAJA.answerQuery(DARAJA.lastCheckoutRequestId(), new FakeDaraja.QueryAnswer(0, "ok"));
        sweeper.sweep();

        Refund planned = returned(sent.getSaleId(), "232.00", PaymentMethod.MPESA).getFirst();

        assertThat(planned.getStatus()).isEqualTo(RefundStatus.REQUIRES_ACTION);
        assertThat(planned.getActionReason()).isEqualTo("MPESA_RECEIPT_UNKNOWN");
    }

    @Test
    void aFailedReversalIsRaisedForAPerson() throws Exception {
        PaymentIntent paid = paidByMpesa("232.00", "QKF1");
        Refund planned = returned(paid.getSaleId(), "232.00", PaymentMethod.MPESA).getFirst();
        refundDispatcher.dispatchDue();

        reversalResult("oc-1", 2001, null).andExpect(status().isOk());

        Refund failed = refunds.findById(planned.getId()).orElseThrow();
        assertThat(failed.getStatus()).isEqualTo(RefundStatus.REQUIRES_ACTION);
        assertThat(failed.getActionReason()).isEqualTo("REVERSAL_FAILED");
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_REFUNDED)).isZero();
    }

    @Test
    @DisplayName("a card refund is run on the terminal and captured")
    void aCardRefundIsCaptured() throws Exception {
        PaymentIntent card = requestAndDispatch(request(PaymentMethod.CARD, "450.00", null, "T-1"));
        intents.capture(card.getId(), "A1B2", null);

        Refund planned = returned(card.getSaleId(), "450.00", PaymentMethod.CARD).getFirst();
        assertThat(planned.getStatus()).isEqualTo(RefundStatus.AWAITING_CAPTURE);

        mockMvc.perform(
                        post("/api/v1/refunds/" + planned.getId() + "/capture")
                                .with(supervisor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"terminalReference\":\"T-1-REF-99\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("COMPLETED")));

        // A card refund cannot be "settled by hand": it is captured or it is not done.
        mockMvc.perform(
                        post("/api/v1/refunds/" + planned.getId() + "/settle")
                                .with(supervisor())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"via\":\"CASH\",\"note\":\"again\"}"))
                .andExpect(status().isConflict());
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_REFUNDED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a cash return is the till's business; nothing is planned here")
    void aCashReturnIsIgnored() {
        PaymentIntent paid = paidByMpesa("232.00", "QKC1");
        EventEnvelope<ReturnProcessedPayload> event =
                returnEvent(paid.getSaleId(), "232.00", PaymentMethod.CASH);

        publish(Topics.SALES_RETURN_PROCESSED, event, paid.getSaleId());
        eventually(Duration.ofSeconds(30), "the return to be handled", () -> handled(event));

        assertThat(refunds.findByReturnId(event.payload().returnId())).isEmpty();
    }

    @Test
    @DisplayName("a redelivered return refunds once")
    void aRedeliveredReturnRefundsOnce() {
        PaymentIntent paid = paidByMpesa("232.00", "QKD1");
        EventEnvelope<ReturnProcessedPayload> event =
                returnEvent(paid.getSaleId(), "232.00", PaymentMethod.MPESA);

        publish(Topics.SALES_RETURN_PROCESSED, event, paid.getSaleId());
        eventually(Duration.ofSeconds(30), "the first delivery", () -> handled(event));
        publish(Topics.SALES_RETURN_PROCESSED, event, paid.getSaleId());
        eventually(Duration.ofSeconds(15), "the redelivery", () -> handled(event));

        assertThat(refunds.findByReturnId(event.payload().returnId())).hasSize(1);
    }

    @Test
    @DisplayName("two returns against one sale can never refund more than was paid")
    void refundsNeverExceedWhatWasPaid() {
        PaymentIntent paid = paidByMpesa("232.00", "QKE1");

        returned(paid.getSaleId(), "200.00", PaymentMethod.MPESA);
        List<Refund> second = returned(paid.getSaleId(), "100.00", PaymentMethod.MPESA);

        assertThat(second)
                .singleElement()
                .extracting(Refund::getAmount)
                .satisfies(amount -> assertThat(amount).isEqualByComparingTo("32.00"));
        BigDecimal refunded =
                jdbc.sql("SELECT amount_refunded FROM payment.payments WHERE intent_id = :id")
                        .param("id", paid.getId())
                        .query(BigDecimal.class)
                        .single();
        assertThat(refunded).isEqualByComparingTo("232.00");
    }

    @Test
    void settlingNeedsTheRefundPermission() throws Exception {
        PaymentIntent paid = paidByMpesa("232.00", "QKG1");
        Refund planned = returned(paid.getSaleId(), "100.00", PaymentMethod.MPESA).getFirst();

        mockMvc.perform(
                        post("/api/v1/refunds/" + planned.getId() + "/settle")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"via\":\"CASH\",\"note\":\"x\"}"))
                .andExpect(status().isForbidden());
        mockMvc.perform(get("/api/v1/refunds/" + planned.getId()).with(supervisor()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.actionReason", is("MPESA_PARTIAL_REFUND")));
    }

    // --- helpers ----------------------------------------------------------------

    private PaymentIntent paidByMpesa(String amount, String receipt) {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, amount, "0712345678", null));
        try {
            stkCallback(
                            DARAJA.lastCheckoutRequestId(),
                            0,
                            receipt,
                            new BigDecimal(amount)
                                    .setScale(0, java.math.RoundingMode.HALF_UP)
                                    .toPlainString())
                    .andExpect(status().isOk());
        } catch (Exception e) {
            throw new IllegalStateException(e);
        }
        return intents.require(sent.getId());
    }

    private List<Refund> returned(UUID saleId, String amount, PaymentMethod method) {
        EventEnvelope<ReturnProcessedPayload> event = returnEvent(saleId, amount, method);
        publish(Topics.SALES_RETURN_PROCESSED, event, saleId);
        eventually(Duration.ofSeconds(30), "the return to be planned", () -> handled(event));
        return refunds.findByReturnId(event.payload().returnId());
    }

    private EventEnvelope<ReturnProcessedPayload> returnEvent(
            UUID saleId, String amount, PaymentMethod method) {
        return EventEnvelope.<ReturnProcessedPayload>builder()
                .topic(Topics.SALES_RETURN_PROCESSED)
                .branchId(BRANCH)
                .payload(
                        new ReturnProcessedPayload(
                                UUID.randomUUID(),
                                saleId,
                                BRANCH,
                                CASHIER,
                                Instant.now(),
                                List.of(),
                                new BigDecimal(amount),
                                "KES",
                                method,
                                null))
                .build();
    }

    private boolean handled(EventEnvelope<?> event) {
        Long count =
                jdbc.sql("SELECT count(*) FROM payment.processed_event WHERE event_id = :id")
                        .param("id", event.eventId())
                        .query(Long.class)
                        .single();
        return count != null && count > 0;
    }

    private ResultActions reversalResult(String originator, int code, String transactionId)
            throws Exception {
        return mockMvc.perform(
                post("/api/v1/payments/mpesa/callbacks/reversal/" + CALLBACK_TOKEN)
                        .contentType(MediaType.APPLICATION_JSON)
                        .content(
                                """
                                {"Result":{"ResultType":0,"ResultCode":%d,"ResultDesc":"%s",\
                                "OriginatorConversationID":"%s","ConversationID":"AG_1",\
                                "TransactionID":%s}}"""
                                        .formatted(
                                                code,
                                                code == 0
                                                        ? "The service request is processed"
                                                                + " successfully."
                                                        : "The initiator information is invalid.",
                                                originator,
                                                transactionId == null
                                                        ? "null"
                                                        : "\"" + transactionId + "\"")));
    }
}
