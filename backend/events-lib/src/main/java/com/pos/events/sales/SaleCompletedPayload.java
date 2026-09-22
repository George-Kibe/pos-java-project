package com.pos.events.sales;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

/**
 * A sale was paid for and is final.
 *
 * <p>Consumed by inventory (deduct stock), customer (accrue loyalty), reporting (project read
 * models) and notification (email the receipt). Carries the priced lines as they were charged - not
 * references to look up - because a consumer reading this a week later must see what the customer
 * actually paid, not what the product costs today.
 *
 * @param cartId the cart stock was held under while the basket was open (inventory reservation
 *     reference type {@code Cart}). Inventory consumes those holds when it deducts, so they stop
 *     counting against availability. Null for a sale with no holds, such as one synced from an
 *     offline terminal, and on events published before the field existed.
 * @param payments how it was paid, as the drawer keeps it: cash is what stayed in the drawer after
 *     change, not the notes handed over. Cash never leaves sales as an event of its own, so this is
 *     the only place a report can learn the split. Empty on events published before the field
 *     existed.
 */
public record SaleCompletedPayload(
        UUID saleId,
        String receiptNumber,
        UUID branchId,
        UUID registerId,
        UUID shiftId,
        UUID cashierId,
        UUID customerId,
        Instant completedAt,
        List<SaleLine> lines,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        String currency,
        UUID cartId,
        List<Tender> payments) {

    /** One tender as settled. */
    public record Tender(com.pos.events.payments.PaymentMethod method, BigDecimal amount) {}

    /**
     * One line as charged.
     *
     * @param quantity fractional for weighed goods
     * @param batchNumber set only when the till already knows which batch was taken, which is
     *     unusual; inventory normally decides by expiry order
     */
    public record SaleLine(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantity,
            BigDecimal unitPrice,
            BigDecimal lineTotal,
            BigDecimal taxAmount,
            String taxClassCode,
            String batchNumber) {}
}
