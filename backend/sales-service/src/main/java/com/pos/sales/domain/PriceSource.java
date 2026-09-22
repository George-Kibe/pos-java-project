package com.pos.sales.domain;

/** Where a line's price came from, snapshotted for the receipt and for margin reporting. */
public enum PriceSource {
    BASE,
    PRICE_LIST,

    /** A human decided. Always accompanied by a reason and an approver. */
    OVERRIDE
}
