package com.pos.sales.domain;

/** Where a sale came from. */
public enum SaleOrigin {
    /** Rung up against a live server, priced by catalog at the moment of sale. */
    ONLINE,

    /**
     * Created by a terminal with no network and replayed later. Priced from a cache that may have
     * been stale, so the server revalidates and flags a variance rather than trusting it.
     */
    OFFLINE_SYNC
}
