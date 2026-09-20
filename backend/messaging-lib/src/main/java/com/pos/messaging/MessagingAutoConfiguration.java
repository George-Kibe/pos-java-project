package com.pos.messaging;

import javax.sql.DataSource;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.boot.context.properties.EnableConfigurationProperties;
import org.springframework.context.annotation.Bean;
import org.springframework.jdbc.core.simple.JdbcClient;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.scheduling.annotation.EnableScheduling;
import org.springframework.transaction.PlatformTransactionManager;
import org.springframework.transaction.support.TransactionTemplate;

import com.pos.messaging.idempotency.IdempotentConsumer;
import com.pos.messaging.outbox.OutboxProperties;
import com.pos.messaging.outbox.OutboxPublisher;
import com.pos.messaging.outbox.OutboxRecorder;
import com.pos.messaging.outbox.OutboxRelayScheduler;

/**
 * Wires the outbox and the idempotency ledger into any service that has a DataSource.
 *
 * <p>The recorder and the idempotent consumer need only the database, so they are available even in
 * a service with no Kafka producer configured. The relay additionally needs a KafkaTemplate and can
 * be disabled outright in a consume-only service.
 */
@AutoConfiguration(
        afterName = {
            "org.springframework.boot.jdbc.autoconfigure.DataSourceAutoConfiguration",
            "org.springframework.boot.kafka.autoconfigure.KafkaAutoConfiguration"
        })
@ConditionalOnClass({JdbcClient.class, DataSource.class})
@ConditionalOnBean(DataSource.class)
@EnableConfigurationProperties(OutboxProperties.class)
public class MessagingAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean
    public JdbcClient posJdbcClient(DataSource dataSource) {
        return JdbcClient.create(dataSource);
    }

    @Bean
    @ConditionalOnMissingBean
    public OutboxRecorder outboxRecorder(JdbcClient jdbcClient) {
        return new OutboxRecorder(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    public IdempotentConsumer idempotentConsumer(JdbcClient jdbcClient) {
        return new IdempotentConsumer(jdbcClient);
    }

    @Bean
    @ConditionalOnMissingBean
    @ConditionalOnBean(KafkaTemplate.class)
    public OutboxPublisher outboxPublisher(
            JdbcClient jdbcClient,
            KafkaTemplate<String, String> kafkaTemplate,
            PlatformTransactionManager transactionManager,
            OutboxProperties properties) {
        return new OutboxPublisher(
                jdbcClient, kafkaTemplate, new TransactionTemplate(transactionManager), properties);
    }

    /** The scheduled relay. Off when {@code pos.outbox.enabled=false}. */
    @AutoConfiguration
    @ConditionalOnBean(OutboxPublisher.class)
    @ConditionalOnProperty(
            prefix = "pos.outbox",
            name = "enabled",
            havingValue = "true",
            matchIfMissing = true)
    @EnableScheduling
    public static class RelaySchedulingConfiguration {

        @Bean
        @ConditionalOnMissingBean
        public OutboxRelayScheduler outboxRelayScheduler(OutboxPublisher publisher) {
            return new OutboxRelayScheduler(publisher);
        }
    }
}
