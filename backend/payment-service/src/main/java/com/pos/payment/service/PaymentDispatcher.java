package com.pos.payment.service;

import java.sql.Timestamp;
import java.time.Clock;
import java.time.Duration;
import java.util.EnumMap;
import java.util.List;
import java.util.Map;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.dao.DataIntegrityViolationException;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Component;

import com.pos.events.payments.PaymentMethod;
import com.pos.payment.provider.PaymentProvider;

/**
 * Sends accepted intents to their provider.
 *
 * <p>Separate from accepting them on purpose. The Kafka listener only records the intent; the
 * provider is called here, after that commit, outside any transaction. Were the push made inside
 * the listener's transaction, a rollback after it - or a redelivery - would prompt the customer's
 * phone a second time.
 *
 * <p>Claims use {@code FOR UPDATE SKIP LOCKED}, so two instances never dispatch the same intent.
 */
@Component
public class PaymentDispatcher {

    private static final Logger log = LoggerFactory.getLogger(PaymentDispatcher.class);

    private static final int BATCH = 20;

    /** Long past any provider timeout: a claim this old belongs to a process that died. */
    static final Duration STUCK_AFTER = Duration.ofMinutes(2);

    private final JdbcClient jdbc;
    private final PaymentIntentService intents;
    private final Map<PaymentMethod, PaymentProvider> providers =
            new EnumMap<>(PaymentMethod.class);
    private final Clock clock;

    public PaymentDispatcher(
            JdbcClient jdbc,
            PaymentIntentService intents,
            List<PaymentProvider> providers,
            Clock clock) {
        this.jdbc = jdbc;
        this.intents = intents;
        this.clock = clock;
        providers.forEach(provider -> this.providers.put(provider.method(), provider));
    }

    @Scheduled(
            fixedDelayString = "${pos.payment.dispatch-interval:PT0.5S}",
            initialDelayString = "${pos.payment.dispatch-interval:PT0.5S}")
    public void dispatchDue() {
        recoverStuck();
        for (UUID id : claim()) {
            dispatch(id);
        }
    }

    void dispatch(UUID intentId) {
        PaymentProvider.Request request = intents.toRequest(intentId);
        PaymentProvider provider = providers.get(methodOf(intentId));

        PaymentProvider.Outcome outcome;
        if (provider == null) {
            outcome =
                    new PaymentProvider.Outcome.Failed(
                            "METHOD_NOT_SUPPORTED",
                            "This installation cannot take " + methodOf(intentId) + " payments");
        } else {
            try {
                outcome = provider.initiate(request);
            } catch (RuntimeException e) {
                log.error("Provider failed for intent {}", intentId, e);
                outcome = new PaymentProvider.Outcome.Failed("PROVIDER_ERROR", e.getMessage());
            }
        }

        try {
            intents.recordOutcome(intentId, outcome);
        } catch (DataIntegrityViolationException e) {
            // A callback inserted this push's row at the same instant; the second try finds it.
            intents.recordOutcome(intentId, outcome);
        }
    }

    private List<UUID> claim() {
        return jdbc.sql(
                        """
                        UPDATE payment_intents
                        SET status = 'DISPATCHING', dispatch_attempts = dispatch_attempts + 1,
                            dispatched_at = :now, updated_at = :now, version = version + 1
                        WHERE id IN (
                            SELECT id FROM payment_intents
                            WHERE status = 'REQUESTED'
                            ORDER BY created_at
                            LIMIT :batch
                            FOR UPDATE SKIP LOCKED)
                        RETURNING id
                        """)
                .param("now", Timestamp.from(clock.instant()))
                .param("batch", BATCH)
                .query(UUID.class)
                .list();
    }

    /**
     * Claims left behind by a process that died mid-call. Cash and card are simply retried - no
     * provider was contacted. M-Pesa is failed: the push may have gone out.
     */
    private void recoverStuck() {
        List<Stuck> stuck =
                jdbc.sql(
                                """
                                SELECT id, method FROM payment_intents
                                WHERE status = 'DISPATCHING' AND dispatched_at < :cutoff
                                """)
                        .param("cutoff", Timestamp.from(clock.instant().minus(STUCK_AFTER)))
                        .query(
                                (rs, row) ->
                                        new Stuck(
                                                rs.getObject("id", UUID.class),
                                                PaymentMethod.valueOf(rs.getString("method"))))
                        .list();
        for (Stuck intent : stuck) {
            log.warn("Intent {} ({}) was left mid-dispatch", intent.id(), intent.method());
            if (intent.method() == PaymentMethod.MPESA) {
                intents.abandonDispatch(intent.id());
            } else {
                jdbc.sql(
                                """
                                UPDATE payment_intents SET status = 'REQUESTED',
                                    version = version + 1
                                WHERE id = :id AND status = 'DISPATCHING'
                                """)
                        .param("id", intent.id())
                        .update();
            }
        }
    }

    private PaymentMethod methodOf(UUID intentId) {
        return intents.require(intentId).getMethod();
    }

    private record Stuck(UUID id, PaymentMethod method) {}
}
