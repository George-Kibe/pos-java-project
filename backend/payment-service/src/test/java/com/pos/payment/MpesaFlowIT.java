package com.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.nio.charset.StandardCharsets;
import java.time.Duration;
import java.util.Base64;
import java.util.Map;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.http.MediaType;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.payment.domain.IntentStatus;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.service.MpesaStatusSweeper;

/**
 * M-Pesa end to end against a fake Daraja: the push, and every way its answer can go wrong.
 *
 * <p>The roadmap's cases are here - a duplicated callback is a no-op, a never-delivered callback is
 * recovered by the status query - with the ones CLAUDE.md warns about: a callback that beats its
 * own push, and money that arrives after the lane has given up.
 */
class MpesaFlowIT extends PaymentTestBase {

    @Autowired private MpesaStatusSweeper sweeper;

    @Test
    @DisplayName("a push, then a callback, authorises the tender to the cent it was asked for")
    void aPushThenACallbackAuthorises() throws Exception {
        EventEnvelope<PaymentRequestedPayload> request =
                request(PaymentMethod.MPESA, "1052.5377", "0712 345 678", null);

        PaymentIntent sent = requestAndDispatch(request);

        assertThat(sent.getStatus()).isEqualTo(IntentStatus.AWAITING_CUSTOMER);
        Map<String, Object> push = DARAJA.pushRequests().getFirst();
        // Whole shillings, rounded half up; the phone in Daraja's form; our callback and token.
        assertThat(push.get("Amount")).isEqualTo(1053);
        assertThat(push.get("PartyA")).isEqualTo("254712345678");
        assertThat(push.get("PhoneNumber")).isEqualTo("254712345678");
        assertThat(push.get("CallBackURL"))
                .isEqualTo(
                        "https://pos.example.test/api/v1/payments/mpesa/callbacks/stk/"
                                + CALLBACK_TOKEN);
        String timestamp = String.valueOf(push.get("Timestamp"));
        assertThat(push.get("Password"))
                .isEqualTo(
                        Base64.getEncoder()
                                .encodeToString(
                                        ("600000test-passkey" + timestamp)
                                                .getBytes(StandardCharsets.UTF_8)));
        assertThat(DARAJA.authorizations().getFirst()).startsWith("Bearer token-");
        // The full number was needed for the push and not a moment longer.
        assertThat(sent.getPhoneNumber()).isNull();
        assertThat(sent.getPhoneMasked()).isEqualTo("*******678");

        stkCallback(DARAJA.lastCheckoutRequestId(), 0, "QKR12XYZ9", "1053.00")
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.ResultCode").value(0));

        PaymentIntent paid = intents.require(sent.getId());
        assertThat(paid.getStatus()).isEqualTo(IntentStatus.AUTHORIZED);
        // Paying exactly the rounded charge settles the four-decimal amount in full.
        assertThat(paid.getAmountAuthorized()).isEqualByComparingTo("1052.5377");
        assertThat(paid.getProviderReference()).isEqualTo("QKR12XYZ9");

        Map<String, Object> payment =
                jdbc.sql(
                                """
                                SELECT amount, amount_charged, rounding_difference,
                                       mpesa_receipt_number
                                FROM payment.payments WHERE intent_id = :id
                                """)
                        .param("id", sent.getId())
                        .query()
                        .singleRow();
        assertThat((BigDecimal) payment.get("amount_charged")).isEqualByComparingTo("1053");
        assertThat((BigDecimal) payment.get("rounding_difference")).isEqualByComparingTo("0.4623");
        assertThat(payment.get("mpesa_receipt_number")).isEqualTo("QKR12XYZ9");

        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
        String event = latestOutboxPayload(Topics.PAYMENTS_PAYMENT_AUTHORIZED);
        assertThat(event)
                .contains("\"providerReference\":\"QKR12XYZ9\"")
                .contains("\"amountAuthorized\":1052.5377")
                // The saga's trace survives the callback: the request's correlation and cause.
                .contains("\"correlationId\":\"corr-" + request.payload().saleId() + "\"")
                .contains("\"causationId\":\"" + request.eventId() + "\"");
    }

    @Test
    @DisplayName("a duplicated callback pays once and announces once")
    void aDuplicateCallbackIsANoOp() throws Exception {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));
        String checkout = DARAJA.lastCheckoutRequestId();

        stkCallback(checkout, 0, "QKA1", "232.00").andExpect(status().isOk());
        stkCallback(checkout, 0, "QKA1", "232.00").andExpect(status().isOk());

        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
        assertThat(paymentsFor(sent.getId())).isEqualTo(1);
    }

    @Test
    @DisplayName("a callback that beats its push's own response is parked, then applied")
    void aCallbackBeforeThePushIsRecorded() throws Exception {
        EventEnvelope<PaymentRequestedPayload> request =
                request(PaymentMethod.MPESA, "232.00", "0712345678", null);
        publish(Topics.PAYMENTS_PAYMENT_REQUESTED, request, request.payload().saleId());
        UUID id = request.payload().paymentIntentId();
        eventually(Duration.ofSeconds(30), "the request", () -> exists(id));

        // The fake numbers pushes from 1 after a reset, so this is the id the push will get.
        stkCallback("ws_CO_1", 0, "QKB1", "232.00").andExpect(status().isOk());
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isZero();

        dispatcher.dispatchDue();

        PaymentIntent paid = intents.require(id);
        assertThat(paid.getStatus()).isEqualTo(IntentStatus.AUTHORIZED);
        assertThat(paid.getProviderReference()).isEqualTo("QKB1");
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a customer who cancels the prompt fails the tender with a reason the till knows")
    void aCancelledPromptFails() throws Exception {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));

        stkCallback(DARAJA.lastCheckoutRequestId(), 1032, null, null).andExpect(status().isOk());

        PaymentIntent failed = intents.require(sent.getId());
        assertThat(failed.getStatus()).isEqualTo(IntentStatus.FAILED);
        assertThat(failed.getFailureCode()).isEqualTo("CANCELLED_BY_USER");
        assertThat(latestOutboxPayload(Topics.PAYMENTS_PAYMENT_FAILED))
                .contains("\"reasonCode\":\"CANCELLED_BY_USER\"");
        assertThat(paymentsFor(sent.getId())).isZero();
    }

    @Test
    @DisplayName("a callback that never comes is recovered by the status query")
    void aLostCallbackIsRecoveredByQuery() {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));
        String checkout = DARAJA.lastCheckoutRequestId();

        // First sweep: still with the customer.
        sweeper.sweep();
        assertThat(intents.require(sent.getId()).getStatus())
                .isEqualTo(IntentStatus.AWAITING_CUSTOMER);

        DARAJA.answerQuery(
                checkout,
                new FakeDaraja.QueryAnswer(0, "The service request is processed successfully."));
        sweeper.sweep();

        PaymentIntent paid = intents.require(sent.getId());
        assertThat(paid.getStatus()).isEqualTo(IntentStatus.AUTHORIZED);
        // The query returns no receipt: the checkout id stands in until reconciliation.
        assertThat(paid.getProviderReference()).isEqualTo(checkout);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    @Test
    @DisplayName("past the give-up point the lane is released, and money that still comes is kept")
    void aTimeoutThenLateMoney() throws Exception {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));
        jdbc.sql(
                        "UPDATE payment.payment_intents SET requested_at = now() - interval '10"
                                + " minutes' WHERE id = :id")
                .param("id", sent.getId())
                .update();

        sweeper.sweep();

        PaymentIntent timedOut = intents.require(sent.getId());
        assertThat(timedOut.getStatus()).isEqualTo(IntentStatus.FAILED);
        assertThat(timedOut.getFailureCode()).isEqualTo("TIMEOUT");
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_FAILED)).isEqualTo(1);

        // The customer entered their PIN after all.
        stkCallback(DARAJA.lastCheckoutRequestId(), 0, "QKL1", "232.00").andExpect(status().isOk());

        PaymentIntent late = intents.require(sent.getId());
        assertThat(late.getStatus()).isEqualTo(IntentStatus.AUTHORIZED);
        assertThat(late.isLate()).isTrue();
        assertThat(paymentsFor(sent.getId())).isEqualTo(1);
        // Announced, so sales can route it to reconciliation rather than the money vanishing.
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    @Test
    void theSweepStopsAskingAfterItsLastAttempt() {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));

        for (int i = 0; i < 5; i++) {
            sweeper.sweep();
        }

        assertThat(DARAJA.queries()).isEqualTo(3);
        assertThat(
                        jdbc.sql(
                                        """
                                        SELECT next_query_at IS NULL FROM payment.mpesa_transactions
                                        WHERE intent_id = :id
                                        """)
                                .param("id", sent.getId())
                                .query(Boolean.class)
                                .single())
                .isTrue();
    }

    @Test
    @DisplayName("a redelivered request prompts the customer's phone once")
    void aRedeliveredRequestPushesOnce() {
        EventEnvelope<PaymentRequestedPayload> request =
                request(PaymentMethod.MPESA, "232.00", "0712345678", null);

        requestAndDispatch(request);
        publish(Topics.PAYMENTS_PAYMENT_REQUESTED, request, request.payload().saleId());
        eventually(
                Duration.ofSeconds(20),
                "the redelivery to be recorded as handled",
                () ->
                        jdbc.sql(
                                                "SELECT count(*) FROM payment.processed_event"
                                                        + " WHERE event_id = :id")
                                        .param("id", request.eventId())
                                        .query(Long.class)
                                        .single()
                                == 1);
        dispatcher.dispatchDue();

        assertThat(DARAJA.pushRequests()).hasSize(1);
    }

    @Test
    void aRefusedPushFailsWithTheProvidersReason() {
        DARAJA.pushMode(FakeDaraja.PushMode.REJECT);
        PaymentIntent refused =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));

        assertThat(refused.getStatus()).isEqualTo(IntentStatus.FAILED);
        assertThat(refused.getFailureCode()).isEqualTo("PROVIDER_REJECTED");
        assertThat(refused.getFailureMessage()).contains("Invalid PhoneNumber");
    }

    @Test
    @DisplayName("Daraja being down fails the tender at once rather than pushing twice later")
    void anUnreachableDarajaFailsFast() {
        DARAJA.pushMode(FakeDaraja.PushMode.DOWN);
        PaymentIntent down =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));

        assertThat(down.getStatus()).isEqualTo(IntentStatus.FAILED);
        assertThat(down.getFailureCode()).isEqualTo("PROVIDER_UNAVAILABLE");
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_FAILED)).isEqualTo(1);
    }

    @Test
    void aNumberThatIsNotAKenyanMobileIsNeverPushed() {
        PaymentIntent refused =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "12345", null));

        assertThat(refused.getFailureCode()).isEqualTo("INVALID_PHONE_NUMBER");
        assertThat(DARAJA.pushRequests()).isEmpty();
    }

    @Test
    @DisplayName("the access token is fetched once and reused, not fetched per push")
    void theTokenIsCached() {
        requestAndDispatch(request(PaymentMethod.MPESA, "100.00", "0712345678", null));
        int afterFirst = DARAJA.tokensIssued();
        requestAndDispatch(request(PaymentMethod.MPESA, "100.00", "0712345678", null));

        assertThat(DARAJA.tokensIssued()).isEqualTo(afterFirst);
        assertThat(DARAJA.authorizations().get(1)).isEqualTo(DARAJA.authorizations().get(0));
    }

    @Test
    @DisplayName("a token Daraja stops honouring early is replaced, and the push still goes out")
    void aRevokedTokenIsReplacedWithoutFailingTheSale() {
        requestAndDispatch(request(PaymentMethod.MPESA, "100.00", "0712345678", null));
        int before = DARAJA.tokensIssued();
        // Daraja answers a dead token with 404.001.03, not 401.
        DARAJA.revokeIssuedTokens();

        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "100.00", "0712345678", null));

        assertThat(sent.getStatus()).isEqualTo(IntentStatus.AWAITING_CUSTOMER);
        assertThat(DARAJA.tokensIssued()).isEqualTo(before + 1);
        assertThat(DARAJA.pushRequests()).hasSize(2);
    }

    @Test
    @DisplayName("an app not subscribed to M-Pesa Express fails the tender and says why")
    void anUnsubscribedAppIsNamedAsTheCause() {
        DARAJA.unsubscribe();

        PaymentIntent refused =
                requestAndDispatch(request(PaymentMethod.MPESA, "100.00", "0712345678", null));

        assertThat(refused.getStatus()).isEqualTo(IntentStatus.FAILED);
        assertThat(refused.getFailureCode()).isEqualTo("PROVIDER_REJECTED");
        assertThat(refused.getFailureMessage()).contains("may not be subscribed");
    }

    @Test
    @DisplayName("a callback without the secret token is refused and changes nothing")
    void aForgedCallbackIsRefused() throws Exception {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));

        mockMvc.perform(
                        post("/api/v1/payments/mpesa/callbacks/stk/guessed-token")
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        stkCallbackBody(
                                                DARAJA.lastCheckoutRequestId(),
                                                0,
                                                "QKF1",
                                                "232.00")))
                .andExpect(status().isNotFound());

        assertThat(intents.require(sent.getId()).getStatus())
                .isEqualTo(IntentStatus.AWAITING_CUSTOMER);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isZero();
    }

    @Test
    @DisplayName("a callback for a push nobody made is kept for reconciliation, not applied")
    void anOrphanCallbackIsParked() throws Exception {
        stkCallback("ws_CO_unknown", 0, "QKO1", "500.00").andExpect(status().isOk());

        assertThat(
                        jdbc.sql(
                                        """
                                        SELECT intent_id IS NULL AND status = 'SUCCEEDED'
                                        FROM payment.mpesa_transactions
                                        WHERE checkout_request_id = 'ws_CO_unknown'
                                        """)
                                .query(Boolean.class)
                                .single())
                .isTrue();
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isZero();
    }

    @Test
    void aMalformedCallbackIsABadRequest() throws Exception {
        mockMvc.perform(
                        post("/api/v1/payments/mpesa/callbacks/stk/" + CALLBACK_TOKEN)
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"Body\":{}}"))
                .andExpect(status().isBadRequest());
    }

    private long paymentsFor(UUID intentId) {
        Long count =
                jdbc.sql("SELECT count(*) FROM payment.payments WHERE intent_id = :id")
                        .param("id", intentId)
                        .query(Long.class)
                        .single();
        return count == null ? 0 : count;
    }
}
