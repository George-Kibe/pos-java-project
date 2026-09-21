package com.pos.events.purchasing;

import java.math.BigDecimal;
import java.time.Instant;
import java.time.LocalDate;
import java.util.List;
import java.util.UUID;

/**
 * A purchase order cleared for sending to the supplier.
 *
 * <p>Carries the totals as approved rather than a reference to fetch them. An approval is the
 * moment a commitment is made, and a consumer reporting on committed spend needs the figure that
 * was actually signed off, not whatever the order says later.
 */
public record PoApprovedPayload(
        UUID purchaseOrderId,
        String orderNumber,
        UUID supplierId,
        String supplierName,
        UUID branchId,
        UUID approvedBy,
        Instant approvedAt,
        LocalDate expectedDeliveryDate,
        List<ApprovedLine> lines,
        BigDecimal netTotal,
        BigDecimal taxTotal,
        BigDecimal grandTotal,
        String currency) {

    public record ApprovedLine(
            UUID productId,
            String sku,
            String productName,
            BigDecimal quantityOrdered,
            BigDecimal unitCost,
            BigDecimal lineTotal) {}
}
