package com.pos.catalog.domain.pricing;

import java.util.UUID;

import com.pos.common.money.Money;

/**
 * One discount that was applied, and why.
 *
 * <p>Carried through to the receipt. "You saved 50" without saying which offer did it is the single
 * most common source of till disputes, and the cashier has no way to answer.
 */
public record AppliedDiscount(
        UUID promotionId, String promotionCode, String promotionName, String type, Money amount) {}
