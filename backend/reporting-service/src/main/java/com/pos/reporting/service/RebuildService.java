package com.pos.reporting.service;

import java.sql.Timestamp;
import java.time.Instant;
import java.util.List;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.id.UuidV7;
import com.pos.common.security.AuthenticatedUser;

import lombok.RequiredArgsConstructor;

/**
 * Throws the read models away and projects them again from the event log.
 *
 * <p>One transaction, holding the rebuild lock exclusively: ingestion waits until it commits, so no
 * event can be projected into a table that is about to be emptied, and nobody reads half a rebuild.
 * Replayed in the order the events arrived, through the same projector, which is why the result is
 * the same numbers - not an approximation of them.
 */
@Service
@RequiredArgsConstructor
public class RebuildService {

    private static final Logger log = LoggerFactory.getLogger(RebuildService.class);

    private static final int BATCH = 1000;

    /** Every table the projector writes. The log itself is never touched. */
    static final List<String> FACT_TABLES =
            List.of(
                    "report_products",
                    "report_sales",
                    "report_sale_lines",
                    "report_sale_tenders",
                    "report_sale_costs",
                    "report_sale_voids",
                    "report_returns",
                    "report_return_lines",
                    "report_shifts",
                    "report_stock_valuations",
                    "report_expiring_batches");

    private final JdbcClient jdbc;
    private final Projector projector;

    public record Result(UUID runId, long eventsReplayed, Instant startedAt, Instant finishedAt) {}

    private record Logged(long seq, String topic, String payload) {}

    @Transactional
    public Result rebuild() {
        Instant started = Instant.now();
        UUID runId = UuidV7.randomUUID();
        jdbc.sql("SELECT pg_advisory_xact_lock(:lock)")
                .param("lock", EventIngestor.REBUILD_LOCK)
                .query()
                .singleRow();

        jdbc.sql("TRUNCATE " + String.join(", ", FACT_TABLES)).update();

        long replayed = 0;
        long after = 0;
        while (true) {
            List<Logged> batch =
                    jdbc.sql(
                                    """
                                    SELECT seq, topic, payload FROM event_log
                                    WHERE seq > :after ORDER BY seq LIMIT :limit
                                    """)
                            .param("after", after)
                            .param("limit", BATCH)
                            .query(
                                    (rs, row) ->
                                            new Logged(
                                                    rs.getLong("seq"),
                                                    rs.getString("topic"),
                                                    rs.getString("payload")))
                            .list();
            if (batch.isEmpty()) {
                break;
            }
            for (Logged event : batch) {
                projector.apply(event.topic(), event.payload());
                after = event.seq();
            }
            replayed += batch.size();
        }

        Instant finished = Instant.now();
        jdbc.sql(
                        """
                        INSERT INTO rebuild_runs
                            (id, started_at, finished_at, events_replayed, requested_by)
                        VALUES (:id, :started, :finished, :count, :by)
                        """)
                .param("id", runId)
                .param("started", Timestamp.from(started))
                .param("finished", Timestamp.from(finished))
                .param("count", replayed)
                .param(
                        "by",
                        AuthenticatedUser.current().map(AuthenticatedUser::userId).orElse(null))
                .update();
        log.info("Rebuilt reporting from {} logged events", replayed);
        return new Result(runId, replayed, started, finished);
    }
}
