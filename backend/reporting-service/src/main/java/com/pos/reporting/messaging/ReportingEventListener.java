package com.pos.reporting.messaging;

import org.apache.kafka.clients.consumer.ConsumerRecord;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.stereotype.Component;

import com.pos.reporting.service.EventIngestor;

import lombok.RequiredArgsConstructor;

/**
 * Everything reporting reads, on one listener.
 *
 * <p>The topic list lives on the projector so what is consumed and what is projected cannot drift
 * apart. Failures retry with backoff and then land in the topic's dead letter, as everywhere.
 */
@Component
@RequiredArgsConstructor
public class ReportingEventListener {

    private final EventIngestor ingestor;

    @KafkaListener(
            topics = "#{T(com.pos.reporting.service.Projector).TOPICS}",
            groupId = "${spring.kafka.consumer.group-id}")
    public void onEvent(ConsumerRecord<String, String> record) {
        ingestor.ingest(record.topic(), record.value());
    }
}
