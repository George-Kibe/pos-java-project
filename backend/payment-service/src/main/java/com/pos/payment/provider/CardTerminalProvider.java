package com.pos.payment.provider;

import org.springframework.stereotype.Component;

import com.pos.events.payments.PaymentMethod;

/**
 * A standalone card terminal the cashier operates.
 *
 * <p>The terminal is not integrated, so the payment waits for the cashier to key in the approval
 * code it printed. The platform never sees card data - a terminal reference and an approval code
 * are all it stores.
 */
@Component
public class CardTerminalProvider implements PaymentProvider {

    @Override
    public PaymentMethod method() {
        return PaymentMethod.CARD;
    }

    @Override
    public Outcome initiate(Request request) {
        return new Outcome.AwaitingCapture();
    }
}
