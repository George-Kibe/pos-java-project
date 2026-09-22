package com.pos.notification.config;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.context.annotation.Bean;
import org.springframework.context.annotation.Configuration;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Retry then dead-letter.
 *
 * <p>Most delivery failures are transient - a mail server refusing a connection for a few seconds,
 * a DNS blip - so the first response is to wait and try again, with the interval doubling so a
 * struggling provider is not hammered.
 *
 * <p>After the attempts are used up the event goes to {@code <topic>.dlt} rather than being
 * discarded or retried forever. Discarding loses a message somebody is waiting for; retrying
 * forever blocks the partition and stops every later message on it. The dead-letter topic keeps the
 * event for a human to look at while the rest of the queue keeps moving.
 */
@Configuration
public class KafkaConsumerConfig {

    private static final Logger log = LoggerFactory.getLogger(KafkaConsumerConfig.class);

    /** Four attempts in total, spread over roughly fifteen seconds. */
    private static final int MAX_RETRIES = 3;

    @Bean
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        // Partition -1 lets the producer choose, because the dead-letter topic has
                        // its own partition count and the original partition may not exist there.
                        (record, exception) -> new TopicPartition(record.topic() + ".dlt", -1));

        // Spring 7 folded ExponentialBackOffWithMaxRetries into ExponentialBackOff.setMaxAttempts.
        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);
        backOff.setMaxAttempts(MAX_RETRIES);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setRetryListeners(
                (record, exception, attempt) ->
                        log.warn(
                                "Delivery attempt {} failed for {} offset {}: {} (root cause {})",
                                attempt,
                                record.topic(),
                                record.offset(),
                                exception.getMessage(),
                                // The class, not the message: a cause's message can carry the
                                // recipient's address or phone number.
                                NestedExceptionUtils.getMostSpecificCause(exception)
                                        .getClass()
                                        .getName()));
        return handler;
    }
}
