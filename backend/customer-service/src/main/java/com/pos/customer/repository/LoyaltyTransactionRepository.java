package com.pos.customer.repository;

import java.math.BigDecimal;
import java.time.Instant;
import java.util.List;
import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;
import org.springframework.data.jpa.repository.Query;

import com.pos.customer.domain.LoyaltyTransaction;
import com.pos.customer.domain.LoyaltyTransactionType;

public interface LoyaltyTransactionRepository extends JpaRepository<LoyaltyTransaction, UUID> {

    Page<LoyaltyTransaction> findByAccountIdOrderByOccurredAtDesc(
            UUID accountId, Pageable pageable);

    List<LoyaltyTransaction> findByCustomerIdOrderByOccurredAt(UUID customerId);

    Optional<LoyaltyTransaction> findBySaleIdAndType(UUID saleId, LoyaltyTransactionType type);

    List<LoyaltyTransaction> findBySaleIdAndTypeIn(UUID saleId, List<LoyaltyTransactionType> types);

    Optional<LoyaltyTransaction> findByPaymentIntentIdAndType(
            UUID paymentIntentId, LoyaltyTransactionType type);

    Optional<LoyaltyTransaction> findByReturnIdAndType(UUID returnId, LoyaltyTransactionType type);

    Optional<LoyaltyTransaction> findBySaleIdAndTypeAndReturnIdIsNull(
            UUID saleId, LoyaltyTransactionType type);

    /** Unspent lots, soonest to expire first - the order a spend walks them in. */
    @Query(
            """
            SELECT t FROM LoyaltyTransaction t
            WHERE t.accountId = :accountId AND t.pointsRemaining > 0
            ORDER BY t.expiresAt ASC NULLS LAST, t.id ASC
            """)
    List<LoyaltyTransaction> lotsOf(UUID accountId);

    /** Lots that have lapsed and still hold points. */
    @Query(
            """
            SELECT t FROM LoyaltyTransaction t
            WHERE t.pointsRemaining > 0 AND t.expiresAt IS NOT NULL AND t.expiresAt <= :at
            ORDER BY t.expiresAt
            """)
    List<LoyaltyTransaction> lapsedLots(Instant at, Pageable pageable);

    /**
     * Spend inside the tier window: what was earned on, less what went back. Refunds reduce a
     * member's standing exactly as the sale raised it.
     */
    @Query(
            """
            SELECT COALESCE(SUM(CASE WHEN t.type = com.pos.customer.domain.LoyaltyTransactionType.ACCRUAL
                                     THEN t.amount ELSE -t.amount END), 0)
            FROM LoyaltyTransaction t
            WHERE t.accountId = :accountId
              AND t.type IN (com.pos.customer.domain.LoyaltyTransactionType.ACCRUAL,
                             com.pos.customer.domain.LoyaltyTransactionType.CLAWBACK)
              AND t.amount IS NOT NULL
              AND t.occurredAt >= :since
            """)
    BigDecimal spendSince(UUID accountId, Instant since);
}
