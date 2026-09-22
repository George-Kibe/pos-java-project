package com.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.post;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.http.MediaType;

import com.pos.events.EventEnvelope;
import com.pos.events.Topics;
import com.pos.events.payments.PaymentMethod;
import com.pos.events.payments.PaymentRequestedPayload;
import com.pos.payment.domain.IntentStatus;
import com.pos.payment.domain.PaymentIntent;

/** Card capture, the other tenders, and who may see and do what. */
class PaymentApiIT extends PaymentTestBase {

    // --- card -------------------------------------------------------------------

    @Test
    @DisplayName("a card tender waits for the approval code, then authorises")
    void aCardIsCapturedByTheCashier() throws Exception {
        PaymentIntent waiting =
                requestAndDispatch(request(PaymentMethod.CARD, "450.00", null, "TERM-7"));
        assertThat(waiting.getStatus()).isEqualTo(IntentStatus.AWAITING_CAPTURE);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isZero();

        mockMvc.perform(
                        post("/api/v1/payments/" + waiting.getId() + "/capture")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(
                                        "{\"approvalCode\":\"A1B2C3\",\"terminalReference\":\"TERM-7-000812\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("AUTHORIZED")))
                .andExpect(jsonPath("$.approvalCode", is("A1B2C3")))
                .andExpect(jsonPath("$.providerReference", is("TERM-7-000812")));

        assertThat(latestOutboxPayload(Topics.PAYMENTS_PAYMENT_AUTHORIZED))
                .contains("\"approvalCode\":\"A1B2C3\"")
                .contains("\"method\":\"CARD\"");
    }

    @Test
    @DisplayName("a retried capture, with or without its Idempotency-Key, pays once")
    void aRetriedCaptureIsOnePayment() throws Exception {
        PaymentIntent waiting =
                requestAndDispatch(request(PaymentMethod.CARD, "450.00", null, "TERM-7"));
        String body = "{\"approvalCode\":\"A1B2C3\"}";

        for (int i = 0; i < 2; i++) {
            mockMvc.perform(
                            post("/api/v1/payments/" + waiting.getId() + "/capture")
                                    .with(cashier())
                                    .header("Idempotency-Key", "capture-1")
                                    .contentType(MediaType.APPLICATION_JSON)
                                    .content(body))
                    .andExpect(status().isOk());
        }
        // No key at all: the state machine recognises the same capture.
        mockMvc.perform(
                        post("/api/v1/payments/" + waiting.getId() + "/capture")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content(body))
                .andExpect(status().isOk());

        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    @Test
    void aDeclinedCardFailsTheTender() throws Exception {
        PaymentIntent waiting =
                requestAndDispatch(request(PaymentMethod.CARD, "450.00", null, "TERM-7"));

        mockMvc.perform(
                        post("/api/v1/payments/" + waiting.getId() + "/decline")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"reason\":\"Insufficient funds on card\"}"))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("FAILED")))
                .andExpect(jsonPath("$.failureCode", is("CARD_DECLINED")));

        // Captured after a decline: refused, not quietly turned into a payment.
        mockMvc.perform(
                        post("/api/v1/payments/" + waiting.getId() + "/capture")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approvalCode\":\"LATE\"}"))
                .andExpect(status().isConflict());
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_FAILED)).isEqualTo(1);
    }

    @Test
    void anApprovalCodeIsOnlyLettersDigitsAndDashes() throws Exception {
        PaymentIntent waiting =
                requestAndDispatch(request(PaymentMethod.CARD, "450.00", null, "TERM-7"));

        // Something shaped like a card number is not an approval code and is not accepted.
        mockMvc.perform(
                        post("/api/v1/payments/" + waiting.getId() + "/capture")
                                .with(cashier())
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approvalCode\":\"4111 1111 1111 1111\"}"))
                .andExpect(status().isBadRequest());
    }

    // --- the other tenders ---------------------------------------------------------

    @Test
    void cashThatReachesThisServiceIsAuthorisedAtOnce() {
        PaymentIntent cash = requestAndDispatch(request(PaymentMethod.CASH, "116.00", null, null));

        assertThat(cash.getStatus()).isEqualTo(IntentStatus.AUTHORIZED);
        assertThat(cash.getAmountAuthorized()).isEqualByComparingTo("116.00");
    }

    @Test
    @DisplayName("a method with no provider fails at once rather than leaving the lane waiting")
    void anUnsupportedMethodFailsFast() {
        PaymentIntent voucher =
                requestAndDispatch(request(PaymentMethod.VOUCHER, "100.00", null, null));

        assertThat(voucher.getStatus()).isEqualTo(IntentStatus.FAILED);
        assertThat(voucher.getFailureCode()).isEqualTo("METHOD_NOT_SUPPORTED");
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_FAILED)).isEqualTo(1);
    }

    @Test
    @DisplayName("a loyalty tender is left to customer-service, not failed here")
    void aDelegatedTenderIsIgnored() {
        EventEnvelope<PaymentRequestedPayload> request =
                request(PaymentMethod.LOYALTY, "100.00", null, null);

        publish(Topics.PAYMENTS_PAYMENT_REQUESTED, request, request.payload().saleId());
        // Wait for a request that should leave no trace: give it time to be wrong.
        EventEnvelope<PaymentRequestedPayload> next =
                request(PaymentMethod.CASH, "100.00", null, null);
        requestAndDispatch(next);

        assertThat(exists(request.payload().paymentIntentId())).isFalse();
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_FAILED)).isZero();
    }

    @Test
    @DisplayName("each tender of a split payment is settled on its own")
    void aSplitTenderIsTwoIntents() throws Exception {
        UUID sale = UUID.randomUUID();
        PaymentIntent card =
                requestAndDispatch(requestFor(sale, PaymentMethod.CARD, "300.00", null, "T-1"));
        PaymentIntent mpesa =
                requestAndDispatch(
                        requestFor(sale, PaymentMethod.MPESA, "152.00", "0712345678", null));

        stkCallback(DARAJA.lastCheckoutRequestId(), 0, "QKS1", "152.00").andExpect(status().isOk());

        mockMvc.perform(get("/api/v1/payments/sales/" + sale).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$", hasSize(2)));
        assertThat(intents.require(mpesa.getId()).getStatus()).isEqualTo(IntentStatus.AUTHORIZED);
        assertThat(intents.require(card.getId()).getStatus())
                .isEqualTo(IntentStatus.AWAITING_CAPTURE);
        assertThat(outboxCount(Topics.PAYMENTS_PAYMENT_AUTHORIZED)).isEqualTo(1);
    }

    // --- reading -------------------------------------------------------------------

    @Test
    @DisplayName("a payment reads with its M-Pesa transaction and its history, never a full number")
    void aPaymentReadsWithItsWorking() throws Exception {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, "232.00", "0712345678", null));

        mockMvc.perform(get("/api/v1/payments/" + sent.getId()).with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.status", is("AWAITING_CUSTOMER")))
                .andExpect(jsonPath("$.phoneMasked", is("*******678")))
                .andExpect(
                        jsonPath("$.mpesa.checkoutRequestId", is(DARAJA.lastCheckoutRequestId())))
                .andExpect(jsonPath("$.mpesa.status", is("PENDING")));

        mockMvc.perform(get("/api/v1/payments/" + sent.getId() + "/events").with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$[0].type", is("REQUESTED")))
                .andExpect(jsonPath("$[1].type", is("STK_PUSH_SENT")));

        mockMvc.perform(
                        get("/api/v1/payments")
                                .param("branchId", BRANCH.toString())
                                .with(cashier()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    // --- authorization ---------------------------------------------------------------

    @Test
    void paymentsAreNotPublic() throws Exception {
        mockMvc.perform(get("/api/v1/payments").param("branchId", BRANCH.toString()))
                .andExpect(status().isUnauthorized());
    }

    @Test
    @DisplayName("a cashier at another branch cannot see or capture this branch's payment")
    void paymentsAreBranchScoped() throws Exception {
        PaymentIntent waiting =
                requestAndDispatch(request(PaymentMethod.CARD, "450.00", null, "TERM-7"));

        mockMvc.perform(
                        get("/api/v1/payments/" + waiting.getId())
                                .with(at(OTHER_BRANCH, UUID.randomUUID(), "payment:take")))
                .andExpect(status().isForbidden());
        mockMvc.perform(
                        post("/api/v1/payments/" + waiting.getId() + "/capture")
                                .with(at(OTHER_BRANCH, UUID.randomUUID(), "payment:take"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approvalCode\":\"A1\"}"))
                .andExpect(status().isForbidden())
                .andExpect(
                        header().string(
                                        "Content-Type",
                                        org.hamcrest.Matchers.containsString("problem+json")));

        assertThat(intents.require(waiting.getId()).getStatus())
                .isEqualTo(IntentStatus.AWAITING_CAPTURE);
    }

    @Test
    void capturingNeedsPaymentTake() throws Exception {
        PaymentIntent waiting =
                requestAndDispatch(request(PaymentMethod.CARD, "450.00", null, "TERM-7"));

        mockMvc.perform(
                        post("/api/v1/payments/" + waiting.getId() + "/capture")
                                .with(at(BRANCH, UUID.randomUUID(), "report:view:branch"))
                                .contentType(MediaType.APPLICATION_JSON)
                                .content("{\"approvalCode\":\"A1\"}"))
                .andExpect(status().isForbidden());
    }

    @Test
    void anUnknownPaymentIsA404() throws Exception {
        mockMvc.perform(get("/api/v1/payments/" + UUID.randomUUID()).with(cashier()))
                .andExpect(status().isNotFound());
    }
}
