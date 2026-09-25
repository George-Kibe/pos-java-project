package com.pos.inventory.domain;

/**
 * Why stock moved.
 *
 * <p>Every row in the ledger carries one. "Stock went down by 40" is not an answer to anything; "40
 * written off as damage" is a management report.
 */
public enum MovementType {
    /** Goods arrived from a supplier. */
    RECEIPT,
    /** Sold at a till. */
    SALE,
    /** Came back from a customer and was resaleable. */
    RETURN,
    /** A deliberate correction, always with a reason code. */
    ADJUSTMENT,
    /** Damaged, expired or otherwise destroyed. */
    WRITE_OFF,
    TRANSFER_OUT,
    TRANSFER_IN,
    /** Posted from a physical count. */
    STOCK_TAKE,
    /** The starting figure when a product is first stocked at a branch. */
    OPENING_BALANCE,
    /** Sent back to the supplier it came from. */
    SUPPLIER_RETURN
}
