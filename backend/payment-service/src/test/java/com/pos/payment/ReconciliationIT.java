package com.pos.payment;

import static org.assertj.core.api.Assertions.assertThat;
import static org.hamcrest.Matchers.hasSize;
import static org.hamcrest.Matchers.is;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.get;
import static org.springframework.test.web.servlet.request.MockMvcRequestBuilders.multipart;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.header;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.jsonPath;
import static org.springframework.test.web.servlet.result.MockMvcResultMatchers.status;

import java.math.BigDecimal;
import java.math.RoundingMode;
import java.nio.charset.StandardCharsets;
import java.time.LocalDate;
import java.util.UUID;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.mock.web.MockMultipartFile;
import org.springframework.test.web.servlet.request.RequestPostProcessor;

import com.jayway.jsonpath.JsonPath;

import com.pos.events.payments.PaymentMethod;
import com.pos.payment.domain.PaymentIntent;
import com.pos.payment.domain.policy.MpesaStatement;
import com.pos.payment.service.MpesaStatusSweeper;

/** A day's M-Pesa statement, uploaded, against what the tills recorded. */
class ReconciliationIT extends PaymentTestBase {

    @Autowired private MpesaStatusSweeper sweeper;

    private static RequestPostProcessor accountant() {
        return at(BRANCH, UUID.randomUUID(), "payment:reconcile");
    }

    @Test
    @DisplayName("each receipt is matched, mismatched, recovered or flagged - never netted off")
    void aStatementIsReconciledReceiptByReceipt() throws Exception {
        paidByMpesa("1052.5377", "QK1"); // charged 1053, on the statement: matched
        paidByMpesa("232.00", "QK2"); // on the statement as 230: mismatch
        paidByMpesa("99.00", "QK9"); // not on the statement: recorded only
        PaymentIntent recovered = recoveredByQuery("500.00"); // no receipt yet

        String statement =
                """
                Receipt No.,Completion Time,Details,Transaction Status,Paid In,Withdrawn
                QK1,%1$s 09:00:00,Pay Bill,Completed,"1,053.00",
                QK2,%1$s 09:10:00,Pay Bill,Completed,230.00,
                QK3,%1$s 09:20:00,Pay Bill,Completed,500.00,
                QK4,%1$s 09:30:00,Pay Bill from a stranger,Completed,75.00,
                QK5,%1$s 09:40:00,Pay Bill Charge,Completed,,15.00
                """
                        .formatted(today());

        String body =
                mockMvc.perform(upload(statement).with(accountant()))
                        .andExpect(status().isCreated())
                        .andExpect(header().exists("Location"))
                        .andExpect(jsonPath("$.statementLines", is(4)))
                        .andExpect(jsonPath("$.matched", is(2)))
                        .andExpect(jsonPath("$.amountMismatches", is(1)))
                        .andExpect(jsonPath("$.statementOnly", is(1)))
                        .andExpect(jsonPath("$.recordedOnly", is(1)))
                        .andExpect(jsonPath("$.clean", is(false)))
                        .andExpect(jsonPath("$.items", hasSize(5)))
                        .andReturn()
                        .getResponse()
                        .getContentAsString();

        // Statement 1858 against recorded 1053 + 232 + 99 + 500 = 1884.
        assertThat(new BigDecimal(JsonPath.read(body, "$.varianceTotal").toString()))
                .isEqualByComparingTo("-26");

        // The receipt a status query could not tell us, filled in from the statement.
        assertThat(
                        jdbc.sql(
                                        "SELECT mpesa_receipt_number FROM payment.payments"
                                                + " WHERE intent_id = :id")
                                .param("id", recovered.getId())
                                .query(String.class)
                                .single())
                .isEqualTo("QK3");

        String runId = JsonPath.read(body, "$.id");
        mockMvc.perform(get("/api/v1/payments/reconciliation-runs/" + runId).with(accountant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.items", hasSize(5)));
        mockMvc.perform(get("/api/v1/payments/reconciliation-runs").with(accountant()))
                .andExpect(status().isOk())
                .andExpect(jsonPath("$.content", hasSize(1)));
    }

    @Test
    void theWrongFileIsABadRequest() throws Exception {
        mockMvc.perform(upload("sku,price\nSOAP,116\n").with(accountant()))
                .andExpect(status().isBadRequest())
                .andExpect(jsonPath("$.code", is("reconciliation.not_a_statement")));
    }

    @Test
    void reconcilingIsForFinanceOnly() throws Exception {
        mockMvc.perform(upload("Receipt No.,Paid In\n").with(cashier()))
                .andExpect(status().isForbidden());
    }

    // --- helpers ----------------------------------------------------------------

    private org.springframework.test.web.servlet.request.MockMultipartHttpServletRequestBuilder
            upload(String csv) {
        return multipart("/api/v1/payments/reconciliation-runs")
                .file(
                        new MockMultipartFile(
                                "statement",
                                "statement.csv",
                                "text/csv",
                                csv.getBytes(StandardCharsets.UTF_8)))
                .param("statementDate", today().toString());
    }

    private static LocalDate today() {
        return LocalDate.now(MpesaStatement.NAIROBI);
    }

    private void paidByMpesa(String amount, String receipt) throws Exception {
        requestAndDispatch(request(PaymentMethod.MPESA, amount, "0712345678", null));
        stkCallback(
                        DARAJA.lastCheckoutRequestId(),
                        0,
                        receipt,
                        new BigDecimal(amount).setScale(0, RoundingMode.HALF_UP).toPlainString())
                .andExpect(status().isOk());
    }

    private PaymentIntent recoveredByQuery(String amount) {
        PaymentIntent sent =
                requestAndDispatch(request(PaymentMethod.MPESA, amount, "0712345678", null));
        DARAJA.answerQuery(DARAJA.lastCheckoutRequestId(), new FakeDaraja.QueryAnswer(0, "ok"));
        sweeper.sweep();
        return sent;
    }
}
