package com.pos.events.sales;

import java.time.Instant;
import java.util.UUID;

/**
 * A sale given up on before it was paid: the payment failed, timed out, or the customer left.
 *
 * <p>Nothing was sold, so nothing is deducted. The event exists for the stock the basket held:
 * inventory releases the holds under {@code cartId} on seeing it. Sales cannot release them itself
 * when the cancellation comes from a payment event - there is no caller token to act with - and
 * leaving them to expire keeps the goods unsellable for half an hour.
 *
 * @param cartId the reservation reference (type {@code Cart}); null for a sale that held nothing
 */
public record SaleCancelledPayload(
        UUID saleId,
        UUID branchId,
        UUID registerId,
        UUID cashierId,
        UUID cartId,
        String reason,
        Instant cancelledAt) {}
