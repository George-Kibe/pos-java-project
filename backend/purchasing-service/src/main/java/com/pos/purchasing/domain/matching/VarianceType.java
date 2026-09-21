package com.pos.purchasing.domain.matching;

/** The kinds of disagreement a three-way match can find. */
public enum VarianceType {
    /** Invoiced above the agreed order price - the classic over-billing. */
    PRICE_VARIANCE,

    /** Invoiced for more (or fewer) units than were received. */
    QUANTITY_VARIANCE,

    /** On the invoice but never received. Paying this pays for goods that are not in the shop. */
    NOT_RECEIVED,

    /** Received but not invoiced. Not an overcharge, but the bill is still coming. */
    NOT_INVOICED,

    /** Received more than was ordered, so nobody authorised the excess. */
    OVER_RECEIPT
}
