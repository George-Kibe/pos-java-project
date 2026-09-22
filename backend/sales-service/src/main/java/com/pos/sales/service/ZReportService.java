package com.pos.sales.service;

import java.math.BigDecimal;
import java.util.List;
import java.util.UUID;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.sales.domain.TillSession;
import com.pos.sales.domain.policy.TillReconciliation;
import com.pos.sales.repository.CashMovementRepository;
import com.pos.sales.repository.SaleRepository;

import lombok.RequiredArgsConstructor;

/**
 * The end-of-shift report.
 *
 * <p>Built from the sales as recorded, not from the running counters on the session, so the two can
 * be compared. If they disagree the report says so - which is the only way a drifting counter is
 * ever noticed.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class ZReportService {

    private final SaleRepository sales;
    private final CashMovementRepository movements;
    private final TillSessionService sessions;

    /** Takings for one payment method. */
    public record MethodTakings(String method, long saleCount, BigDecimal total) {}

    /** What a shift took, and whether the drawer agrees. */
    public record ZReport(
            UUID tillSessionId,
            UUID branchId,
            UUID registerId,
            UUID cashierId,
            String status,
            java.time.Instant openedAt,
            java.time.Instant closedAt,
            BigDecimal openingFloat,
            List<MethodTakings> takings,
            BigDecimal totalTakings,
            BigDecimal cashSales,
            BigDecimal cashRefunds,
            BigDecimal cashDrops,
            BigDecimal expectedCash,
            BigDecimal countedCash,
            BigDecimal variance,
            int saleCount,
            List<CashMovementSummary> cashMovements,
            boolean countersAgreeWithSales,
            String currency) {}

    /** One float, drop or pay-out, for the report to show the working. */
    public record CashMovementSummary(
            String type, BigDecimal amount, String reason, java.time.Instant occurredAt) {}

    public ZReport forSession(UUID sessionId) {
        TillSession session = sessions.require(sessionId);

        List<MethodTakings> takings =
                sales.takingsByMethod(sessionId).stream()
                        .map(
                                row ->
                                        new MethodTakings(
                                                row.getMethod(),
                                                row.getSaleCount(),
                                                row.getTotal()))
                        .toList();

        BigDecimal totalTakings =
                takings.stream().map(MethodTakings::total).reduce(BigDecimal.ZERO, BigDecimal::add);

        BigDecimal cashFromSales =
                takings.stream()
                        .filter(row -> "CASH".equals(row.method()))
                        .map(MethodTakings::total)
                        .reduce(BigDecimal.ZERO, BigDecimal::add);

        TillReconciliation reconciliation = session.reconcile(session.getCountedCash());

        List<CashMovementSummary> cashMovements =
                movements.findByTillSessionIdOrderByOccurredAt(sessionId).stream()
                        .map(
                                movement ->
                                        new CashMovementSummary(
                                                movement.getType().name(),
                                                movement.getAmount(),
                                                movement.getReason(),
                                                movement.getOccurredAt()))
                        .toList();

        // The running counter on the session against what the sales actually say. A mismatch is a
        // bug in this service, and a report that cannot show one would hide it.
        boolean agree = session.getCashSales().compareTo(cashFromSales) == 0;

        return new ZReport(
                session.getId(),
                session.getBranchId(),
                session.getRegisterId(),
                session.getCashierId(),
                session.getStatus().name(),
                session.getOpenedAt(),
                session.getClosedAt(),
                session.getOpeningFloat(),
                takings,
                totalTakings,
                session.getCashSales(),
                session.getCashRefunds(),
                session.getCashDrops(),
                reconciliation.expectedCash(),
                session.getCountedCash(),
                session.getVariance(),
                session.getSaleCount(),
                cashMovements,
                agree,
                session.getCurrency());
    }
}
