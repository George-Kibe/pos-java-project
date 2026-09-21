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
        String currency) {

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
