package com.pos.payment.config;

import java.util.List;

import org.springframework.boot.context.properties.ConfigurationProperties;

import com.pos.events.payments.PaymentMethod;

/**
 * @param delegatedMethods tenders another service settles and this one must leave alone. A tender
 *     is authorised by whoever holds the value behind it: loyalty points live in customer-service,
 *     which spends them and answers the request itself. Without this list those requests would be
 *     failed here as METHOD_NOT_SUPPORTED, and the sale cancelled under the customer.
 */
@ConfigurationProperties(prefix = "pos.payment")
public record PaymentProperties(List<PaymentMethod> delegatedMethods) {

    public boolean isDelegated(PaymentMethod method) {
        return delegatedMethods != null && delegatedMethods.contains(method);
    }
}
