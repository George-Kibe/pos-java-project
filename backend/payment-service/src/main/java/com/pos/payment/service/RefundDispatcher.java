package com.pos.payment.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pos.payment.client.daraja.DarajaClient;
import com.pos.payment.client.daraja.DarajaException;

import lombok.RequiredArgsConstructor;

/**
 * Sends M-Pesa reversals, outside any transaction, one claim at a time.
 *
 * <p>A reversal that met a timeout is never resent: it may have gone through, and reversing twice
 * is not possible anyway - the second attempt would fail and muddy the record. It is raised for a
 * person instead.
 */
@Component
@RequiredArgsConstructor
public class RefundDispatcher {

    private static final Logger log = LoggerFactory.getLogger(RefundDispatcher.class);

    private final JdbcClient jdbc;
    private final RefundService refunds;
    private final DarajaClient daraja;
    private final Clock clock;

    @Scheduled(
            fixedDelayString = "${pos.payment.refund-dispatch-interval:PT5S}",
            initialDelayString = "${pos.payment.refund-dispatch-interval:PT5S}")
    public void dispatchDue() {
        recoverStuck();
        for (Claimed claimed : claim()) {
            try {
                DarajaClient.ReversalAccepted accepted =
                        daraja.reverse(
                                claimed.receipt(),
                                claimed.charged(),
                                "Refund for sale " + claimed.saleId());
                refunds.reversalSent(
                        claimed.id(),
                        accepted.originatorConversationId(),
                        accepted.conversationId());
            } catch (DarajaException e) {
                log.warn("Reversal for refund {} not sent: {}", claimed.id(), e.getMessage());
                refunds.reversalNotSent(
                        claimed.id(),
                        e.kind() == DarajaException.Kind.REJECTED
                                ? "REVERSAL_REJECTED"
                                : "REVERSAL_OUTCOME_UNKNOWN",
                        e.getMessage());
            }
        }
    }

    private List<Claimed> claim() {
        return jdbc.sql(
                        """
                        UPDATE refunds r
                        SET status = 'PROCESSING', updated_at = :now, version = r.version + 1
                        FROM payments p
                        WHERE p.id = r.payment_id AND r.id IN (
                            SELECT id FROM refunds
                            WHERE status = 'PENDING_DISPATCH'
                            ORDER BY requested_at
                            LIMIT 10
                            FOR UPDATE SKIP LOCKED)
                        RETURNING r.id, r.sale_id, p.mpesa_receipt_number, p.amount_charged
                        """)
                .param("now", Timestamp.from(clock.instant()))
                .query(
                        (rs, row) ->
                                new Claimed(
                                        rs.getObject("id", UUID.class),
                                        rs.getObject("sale_id", UUID.class),
                                        rs.getString("mpesa_receipt_number"),
                                        rs.getBigDecimal("amount_charged")))
                .list();
    }

    /** A claim with no conversation id after two minutes belongs to a process that died. */
    private void recoverStuck() {
        List<UUID> stuck =
                jdbc.sql(
                                """
                                SELECT id FROM refunds
                                WHERE status = 'PROCESSING' AND originator_conversation_id IS NULL
                                  AND updated_at < :cutoff
                                """)
                        .param(
                                "cutoff",
                                Timestamp.from(
                                        clock.instant().minus(PaymentDispatcher.STUCK_AFTER)))
                        .query(UUID.class)
                        .list();
        for (UUID id : stuck) {
            refunds.reversalNotSent(
                    id, "REVERSAL_INTERRUPTED", "Interrupted mid-request; check the statement");
        }
    }

    private record Claimed(UUID id, UUID saleId, String receipt, java.math.BigDecimal charged) {}
}
