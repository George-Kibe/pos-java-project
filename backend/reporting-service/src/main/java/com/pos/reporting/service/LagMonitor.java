package com.pos.reporting.service;

import java.time.Instant;
import java.util.ArrayList;
import java.util.List;
import java.util.Map;
import java.util.TreeMap;
import java.util.concurrent.TimeUnit;
import java.util.concurrent.atomic.AtomicLong;
import java.util.concurrent.atomic.AtomicReference;

import org.apache.kafka.clients.admin.AdminClient;
import org.apache.kafka.clients.admin.ListOffsetsResult;
import org.apache.kafka.clients.admin.OffsetSpec;
import org.apache.kafka.clients.admin.TopicDescription;
import org.apache.kafka.clients.consumer.OffsetAndMetadata;
import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.beans.factory.annotation.Value;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaAdmin;
import org.springframework.scheduling.annotation.Scheduled;
import org.springframework.stereotype.Service;

import io.micrometer.core.instrument.Gauge;
import io.micrometer.core.instrument.MeterRegistry;

/**
 * How far behind the events the reports are.
 *
 * <p>Every number here is eventually consistent - built from events that may still be on their way
 * - so a report has to be able to say how stale it is. Lag is measured per topic: the end of each
 * partition less what this service has committed. It is refreshed on a schedule rather than on each
 * request, so a dashboard polling it cannot load the broker, and published as a gauge for alerting.
 */
@Service
public class LagMonitor {

    private static final Logger log = LoggerFactory.getLogger(LagMonitor.class);

    private final KafkaAdmin kafkaAdmin;
    private final JdbcClient jdbc;
    private final String groupId;
    private final AtomicLong totalLag = new AtomicLong(-1);
    private final AtomicReference<Lag> last = new AtomicReference<>();

    public record TopicLag(String topic, long lag) {}

    /**
     * @param totalLag events published but not yet taken in; -1 when the broker could not be asked
     * @param lastEventAt when the newest event was taken in - how fresh the reports are
     */
    public record Lag(
            String consumerGroup,
            long totalLag,
            List<TopicLag> topics,
            Instant lastEventAt,
            Instant measuredAt) {}

    public LagMonitor(
            KafkaAdmin kafkaAdmin,
            JdbcClient jdbc,
            MeterRegistry meters,
            @Value("${spring.kafka.consumer.group-id}") String groupId) {
        this.kafkaAdmin = kafkaAdmin;
        this.jdbc = jdbc;
        this.groupId = groupId;
        Gauge.builder("pos.reporting.consumer.lag", totalLag, AtomicLong::get)
                .description("Events published that reporting has not yet taken in")
                .register(meters);
    }

    public Lag current() {
        Lag cached = last.get();
        return cached != null ? cached : measure();
    }

    @Scheduled(
            fixedDelayString = "${pos.reporting.lag-interval:PT30S}",
            initialDelayString = "${pos.reporting.lag-interval:PT30S}")
    public Lag measure() {
        Instant lastEventAt =
                jdbc.sql("SELECT max(received_at) FROM event_log")
                        .query(java.sql.Timestamp.class)
                        .optional()
                        .map(java.sql.Timestamp::toInstant)
                        .orElse(null);
        try (AdminClient admin = AdminClient.create(kafkaAdmin.getConfigurationProperties())) {
            Map<String, TopicDescription> topics =
                    admin.describeTopics(Projector.TOPICS)
                            .allTopicNames()
                            .get(10, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetSpec> latest = new java.util.HashMap<>();
            topics.values()
                    .forEach(
                            topic ->
                                    topic.partitions()
                                            .forEach(
                                                    partition ->
                                                            latest.put(
                                                                    new TopicPartition(
                                                                            topic.name(),
                                                                            partition.partition()),
                                                                    OffsetSpec.latest())));
            Map<TopicPartition, ListOffsetsResult.ListOffsetsResultInfo> ends =
                    admin.listOffsets(latest).all().get(10, TimeUnit.SECONDS);
            Map<TopicPartition, OffsetAndMetadata> committed =
                    admin.listConsumerGroupOffsets(groupId)
                            .partitionsToOffsetAndMetadata()
                            .get(10, TimeUnit.SECONDS);

            Map<String, Long> byTopic = new TreeMap<>();
            ends.forEach(
                    (partition, end) -> {
                        OffsetAndMetadata done = committed.get(partition);
                        // Nothing committed yet: everything on the partition is still to come.
                        long lag = end.offset() - (done == null ? 0 : done.offset());
                        byTopic.merge(partition.topic(), Math.max(lag, 0), Long::sum);
                    });
            List<TopicLag> rows = new ArrayList<>();
            byTopic.forEach((topic, lag) -> rows.add(new TopicLag(topic, lag)));
            long total = rows.stream().mapToLong(TopicLag::lag).sum();
            totalLag.set(total);
            Lag lag = new Lag(groupId, total, rows, lastEventAt, Instant.now());
            last.set(lag);
            return lag;
        } catch (InterruptedException e) {
            Thread.currentThread().interrupt();
            return unmeasured(lastEventAt);
        } catch (Exception e) {
            // Topics not created yet, or the broker away: say so rather than report zero lag.
            log.warn("Could not measure consumer lag: {}", e.toString());
            return unmeasured(lastEventAt);
        }
    }

    private Lag unmeasured(Instant lastEventAt) {
        totalLag.set(-1);
        Lag lag = new Lag(groupId, -1, List.of(), lastEventAt, Instant.now());
        last.set(lag);
        return lag;
    }
}
