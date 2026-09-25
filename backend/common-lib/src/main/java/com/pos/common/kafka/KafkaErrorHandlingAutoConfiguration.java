package com.pos.common.kafka;

import org.apache.kafka.common.TopicPartition;
import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.NestedExceptionUtils;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.DeadLetterPublishingRecoverer;
import org.springframework.kafka.listener.DefaultErrorHandler;
import org.springframework.util.backoff.ExponentialBackOff;

/**
 * Every consumer's failure policy: retry with backoff, then dead-letter to {@code <topic>.dlt}.
 *
 * <p>Retried because most failures are transient - a locked row, a database blip. Dead-lettered
 * rather than retried forever because a stuck event blocks its partition, and behind it is every
 * later event for the aggregates that share it. A DLT arrival is alerted on, never silent.
 *
 * <p>Ordered after Boot's Kafka auto-configuration, whose template the recoverer publishes with:
 * {@code @ConditionalOnBean} only sees beans registered before it. A service with a different
 * policy declares its own {@link CommonErrorHandler}.
 */
@AutoConfiguration(
        afterName = "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration")
@ConditionalOnClass(DefaultErrorHandler.class)
@ConditionalOnBean(KafkaOperations.class)
public class KafkaErrorHandlingAutoConfiguration {

    private static final Logger log =
            LoggerFactory.getLogger(KafkaErrorHandlingAutoConfiguration.class);

    /** Four attempts in all, spread over roughly fifteen seconds. */
    static final long MAX_ATTEMPTS = 3;

    @Bean
    @ConditionalOnMissingBean(CommonErrorHandler.class)
    public DefaultErrorHandler kafkaErrorHandler(KafkaOperations<Object, Object> template) {
        DeadLetterPublishingRecoverer recoverer =
                new DeadLetterPublishingRecoverer(
                        template,
                        // Partition -1 lets the producer choose: the dead-letter topic has its own
                        // partition count, and the original partition may not exist there.
                        (record, exception) -> new TopicPartition(record.topic() + ".dlt", -1));

        ExponentialBackOff backOff = new ExponentialBackOff();
        backOff.setInitialInterval(1_000L);
        backOff.setMultiplier(2.0);
        backOff.setMaxInterval(8_000L);
        backOff.setMaxAttempts(MAX_ATTEMPTS);

        DefaultErrorHandler handler = new DefaultErrorHandler(recoverer, backOff);
        handler.setRetryListeners(
                (record, exception, attempt) ->
                        log.warn(
                                "Attempt {} failed for {} offset {}: {} (root cause {})",
                                attempt,
                                record.topic(),
                                record.offset(),
                                exception.getMessage(),
                                // The class, not the message: a cause's message can carry the
                                // values that failed, and some of those are phone numbers.
                                NestedExceptionUtils.getMostSpecificCause(exception)
                                        .getClass()
                                        .getName()));
        return handler;
    }
}
