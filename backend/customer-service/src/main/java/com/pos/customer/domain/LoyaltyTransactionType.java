package com.pos.customer.domain;

/** Why points moved. Every row in the ledger is one of these. */
public enum LoyaltyTransactionType {
    /** Earned on a sale. Also a lot: it carries its own remaining points and expiry. */
    ACCRUAL,
    /** Spent as a tender. */
    REDEMPTION,
    /** A redemption given back, because the sale it paid for was cancelled or voided. */
    REVERSAL,
    /** Points taken back because goods went back. */
    CLAWBACK,
    /** Lapsed unspent. */
    EXPIRY,
    /** A person moved them, with a reason. */
    ADJUSTMENT;

    /** Types that create points, and therefore a lot to spend from. */
    public boolean createsLot() {
        return this == ACCRUAL || this == REVERSAL || this == ADJUSTMENT;
    }
}
