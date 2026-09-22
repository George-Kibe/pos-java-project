package com.pos.events.payments;

/**
 * How a customer is paying.
 *
 * <p>In the events rather than in one service because three different services need to reason about
 * it: sales decides whether the sale can complete at the till, payment decides which provider to
 * call, and reporting splits takings by method.
 */
public enum PaymentMethod {
    /** Settled at the till. No provider, no waiting, and change may be due. */
    CASH,

    /** M-Pesa STK push. Asynchronous, and the callback may duplicate, reorder or never arrive. */
    MPESA,

    /**
     * A card terminal operated by the cashier. The platform stores a terminal reference and an
     * approval code only - never card data.
     */
    CARD,

    /** Store credit or a gift voucher. */
    VOUCHER,

    /**
     * Loyalty points spent as money. Settled by customer-service, which owns the balance: a tender
     * is authorised by whoever holds the value behind it, and no other service can spend points.
     */
    LOYALTY,

    /** Settled against a customer account for later invoicing. */
    ACCOUNT
}
