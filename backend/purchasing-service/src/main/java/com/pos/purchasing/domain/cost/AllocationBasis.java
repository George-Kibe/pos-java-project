package com.pos.purchasing.domain.cost;

/** How a delivery's charges are spread across its lines. */
public enum AllocationBasis {
    /**
     * In proportion to line value. The usual choice: duty is charged on value, and a pallet of
     * whisky should carry more of the freight bill than a pallet of flour.
     */
    BY_VALUE,

    /**
     * In proportion to quantity. Right when the charge is driven by bulk rather than worth - pallet
     * freight on goods of similar size.
     */
    BY_QUANTITY
}
