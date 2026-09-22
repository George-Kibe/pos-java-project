package com.pos.customer.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry then dead-letter, as in notification-service.
 *
 * <p>Retried because most failures here are transient - a locked row, a momentary database blip.
 * Dead-lettered rather than retried forever because a stuck sale event blocks its partition, and
 * behind it are every later sale at that branch.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    private static final int MAX_RETRIES = 3;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        (record, exception) -> new TopicPartition(record.topic() + ".dlt", -1));

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);
        backOff.setMaxAttempts(MAX_RETRIES);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setRetryListeners(
                (record, exception, attempt) ->
                        log.warn(
                                "Attempt {} failed for {} offset {}: {}",
                                attempt,
                                record.topic(),
                                record.offset(),
                                exception.getMessage()));
        return handler;
    }
}
