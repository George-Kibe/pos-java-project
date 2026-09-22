package com.pos.sales.domain;

/** One tender's life. */
public enum PaymentStatus {
    PENDING,
    AUTHORIZED,
    FAILED,
    REFUNDED
}
