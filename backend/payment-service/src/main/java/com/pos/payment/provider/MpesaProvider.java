package com.pos.payment.provider;

import java.math.BigDecimal;
import java.util.Optional;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Component;

import com.pos.common.contact.PhoneNumbers;
import com.pos.events.payments.PaymentMethod;
import com.pos.payment.client.daraja.DarajaClient;
import com.pos.payment.client.daraja.DarajaException;
import com.pos.payment.client.daraja.DarajaProperties;
import com.pos.payment.domain.policy.MpesaAmount;

import lombok.RequiredArgsConstructor;

/**
 * M-Pesa by STK Push: the customer's phone shows a prompt and they enter their PIN.
 *
 * <p>A push that timed out or met a server error is reported as a failure, never retried. "No
 * answer" includes "the prompt reached the phone", and a second push is a second prompt a customer
 * may well accept.
 */
@Component
@RequiredArgsConstructor
public class MpesaProvider implements PaymentProvider {

    private static final Logger log = LoggerFactory.getLogger(MpesaProvider.class);

    private final DarajaClient daraja;
    private final DarajaProperties properties;

    @Override
    public PaymentMethod method() {
        return PaymentMethod.MPESA;
    }

    @Override
    public Outcome initiate(Request request) {
        if (!properties.isConfigured()) {
            return new Outcome.Failed(
                    "PROVIDER_UNAVAILABLE", "M-Pesa is not configured on this installation");
        }
        Optional<String> msisdn = PhoneNumbers.toMsisdn(request.phoneNumber());
        if (msisdn.isEmpty()) {
            return new Outcome.Failed(
                    "INVALID_PHONE_NUMBER",
                    "Not a Kenyan mobile number; check it with the customer");
        }

        BigDecimal charge = MpesaAmount.chargeFor(request.amount());
        String reference =
                request.receiptNumber() != null ? request.receiptNumber() : shortId(request);
        try {
            DarajaClient.StkPushAccepted accepted =
                    daraja.stkPush(msisdn.get(), charge, reference, "Purchase");
            return new Outcome.AwaitingCustomer(
                    accepted.merchantRequestId(),
                    accepted.checkoutRequestId(),
                    charge,
                    PhoneNumbers.mask(msisdn.get()));
        } catch (DarajaException e) {
            log.warn(
                    "STK Push for intent {} failed ({}): {}",
                    request.intentId(),
                    e.kind(),
                    e.getMessage());
            return new Outcome.Failed(
                    e.kind() == DarajaException.Kind.REJECTED
                            ? "PROVIDER_REJECTED"
                            : "PROVIDER_UNAVAILABLE",
                    e.getMessage());
        }
    }

    private static String shortId(Request request) {
        return request.saleId().toString().replace("-", "").substring(0, 12).toUpperCase();
    }
}
