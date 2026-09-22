package com.pos.payment.provider;

import org.springframework.stereotype.Component;

import com.pos.events.payments.PaymentMethod;

/**
 * Cash, if it ever reaches this service.
 *
 * <p>Sales settles cash at the till - no network between a customer and their change - so in normal
 * running no cash intent arrives here. Should one come, the notes are already in the drawer and
 * there is nothing to wait for.
 */
@Component
public class CashProvider implements PaymentProvider {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CASH;
    }

    @Override
    public Outcome initiate(Request request) {
        return new Outcome.Authorized(request.amount(), null, null);
    }
}
