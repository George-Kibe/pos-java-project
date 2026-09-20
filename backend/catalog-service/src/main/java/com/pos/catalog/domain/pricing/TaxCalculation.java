package com.pos.catalog.domain.pricing;

import java.math.BigDecimal;

import com.pos.common.money.Money;

/**
 * An amount split into its net and tax parts.
 *
 * <p>The three always reconcile exactly: {@code net + tax == gross}. That is guaranteed by deriving
 * one of the three from the other two after rounding, rather than rounding all three independently
 * and hoping. A receipt whose parts do not add up to its total is a receipt a customer will query
 * and an auditor will reject.
 */
public record TaxCalculation(
        Money net, Money tax, Money gross, BigDecimal rate, boolean inclusive) {}
