package com.pos.catalog.domain;

/** How a promotion reduces the price. */
public enum PromotionType {
    /** A share off the line, e.g. 0.10 for 10%. */
    PERCENTAGE_OFF,
    /** A fixed amount off each unit. */
    AMOUNT_OFF,
    /** Buy X, get Y free. The free units are the ones the customer does not pay for. */
    BUY_X_GET_Y,
    /**
     * A fixed price for a set of different products.
     *
     * <p>Modelled here but evaluated in sales-service, because deciding whether a bundle is
     * satisfied needs the whole basket, and this service prices one line at a time.
     */
    BUNDLE
}
