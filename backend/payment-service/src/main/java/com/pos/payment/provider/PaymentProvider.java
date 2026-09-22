package com.pos.payment.provider;

import java.math.BigDecimal;
import java.util.UUID;

import com.pos.events.payments.PaymentMethod;

/**
 * One way of taking money.
 *
 * <p>Called outside any transaction - a provider call may take seconds and must never hold a row
 * lock or a connection - and returns what happened rather than recording it. The caller records the
 * outcome in a transaction of its own.
 */
public interface PaymentProvider {

    PaymentMethod method();

    Outcome initiate(Request request);

    /** What a provider is given: a snapshot, never a managed entity. */
    record Request(
            UUID intentId,
            UUID saleId,
            String receiptNumber,
            BigDecimal amount,
            String currency,
            String phoneNumber,
            String terminalReference) {}

    /** What a provider did. */
    sealed interface Outcome {

        /** Settled on the spot. */
        record Authorized(BigDecimal charged, String providerReference, String approvalCode)
                implements Outcome {}

        /** STK Push sent; the answer comes by callback or status query. */
        record AwaitingCustomer(
                String merchantRequestId,
                String checkoutRequestId,
                BigDecimal charged,
                String phoneMasked)
                implements Outcome {}

        /** The cashier finishes it on a card terminal. */
        record AwaitingCapture() implements Outcome {}

        record Failed(String code, String message) implements Outcome {}
    }
}
