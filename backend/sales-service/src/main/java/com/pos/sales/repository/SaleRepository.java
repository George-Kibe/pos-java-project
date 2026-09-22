package com.pos.sales.repository;

import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.EntityGraph;
import org.springframework.data.jpa.repository.EntityGraph.EntityGraphType;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;
import org.springframework.data.repository.query.Param;

import com.pos.sales.domain.Sale;
import com.pos.sales.domain.SaleStatus;

public interface SaleRepository extends JpaRepository<Sale, UUID> {

    @Override
    @EntityGraph(
            type = EntityGraphType.LOAD,
            attributePaths = {"tillSession"})
    Optional<Sale> findById(UUID id);

    Optional<Sale> findByReceiptNumber(String receiptNumber);

    /** The dedupe check for an offline batch: the terminal's own id. */
    Optional<Sale> findByClientSaleId(UUID clientSaleId);

    boolean existsByClientSaleId(UUID clientSaleId);

    Page<Sale> findByBranchIdOrderByOccurredAtDesc(UUID branchId, Pageable pageable);

    List<Sale> findByTillSessionIdAndStatus(UUID tillSessionId, SaleStatus status);

    /**
     * Sales stuck waiting for a payment answer that may never arrive.
     *
     * <p>The saga's recovery query. A customer who walks away from an M-Pesa prompt leaves a sale
     * here, and it has to be compensated rather than held open forever.
     */
    @Query(
            """
            SELECT s FROM Sale s
            WHERE s.status = com.pos.sales.domain.SaleStatus.AWAITING_PAYMENT
              AND s.occurredAt < :cutoff
            """)
    List<Sale> findStalePendingPayments(@Param("cutoff") Instant cutoff);

    /** Totals for a shift's Z-report, by payment method. */
    @Query(
            value =
                    """
                    SELECT p.method AS method,
                           count(DISTINCT s.id) AS saleCount,
                           COALESCE(SUM(p.amount), 0) AS total
                    FROM sales s
                             JOIN sale_payments p ON p.sale_id = s.id
                    WHERE s.till_session_id = :sessionId
                      AND s.status = 'PAID'
                      AND p.status = 'AUTHORIZED'
                    GROUP BY p.method
                    """,
            nativeQuery = true)
    List<MethodTotal> takingsByMethod(@Param("sessionId") UUID sessionId);

    /**
     * Every shilling of cash a shift took, voided sales included - what the session's running
     * {@code cashSales} counter holds. A void is paid back out as a cash refund rather than
     * subtracted from sales, so comparing the counter with takings net of voids would call every
     * shift with a void a drifting counter.
     */
    @Query(
            value =
                    """
                    SELECT COALESCE(SUM(p.amount), 0)
                    FROM sales s
                             JOIN sale_payments p ON p.sale_id = s.id
                    WHERE s.till_session_id = :sessionId
                      AND s.status IN ('PAID', 'VOIDED')
                      AND p.status = 'AUTHORIZED'
                      AND p.method = 'CASH'
                    """,
            nativeQuery = true)
    java.math.BigDecimal cashTakenIncludingVoided(@Param("sessionId") UUID sessionId);

    /** Projection for the Z-report. */
    interface MethodTotal {
        String getMethod();

        long getSaleCount();

        java.math.BigDecimal getTotal();
    }
}
