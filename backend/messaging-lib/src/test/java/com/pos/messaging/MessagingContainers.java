package com.pos.messaging;

import org.springframework.test.context.DynamicPropertyRegistry;
import org.testcontainers.kafka.ConfluentKafkaContainer;
import org.testcontainers.postgresql.PostgreSQLContainer;

/**
 * The PostgreSQL container shared by this module's tests.
 *
 * <p>Started once for the JVM and never stopped: tying a container to a single test class with
 * {@code @Container} leaves the second class talking to a dead port, because Spring's context cache
 * keeps handing out the context built for the first one.
 */
final class MessagingContainers {

    private MessagingContainers() {}

    // Never closed on purpose: it lives for the whole JVM and Testcontainers' reaper removes it.

    @SuppressWarnings("resource")
    static final PostgreSQLContainer POSTGRES =
            new PostgreSQLContainer("postgres:18-alpine")
                    .withDatabaseName("pos")
                    .withUsername("test")
                    .withPassword("test");

    /**
     * Confluent's distribution rather than the apache/kafka image used in docker-compose. Both are
     * Apache Kafka and speak the same protocol; Testcontainers' apache/kafka container copies a
     * start script in and immediately executes it, which fails with "Text file busy" on this Docker
     * Desktop/gVisor setup.
     */
    static final ConfluentKafkaContainer KAFKA =
            new ConfluentKafkaContainer("confluentinc/cp-kafka:8.3.2");

    static {
        POSTGRES.start();
        KAFKA.start();
    }

    static void registerKafka(DynamicPropertyRegistry registry) {
        registry.add("spring.kafka.bootstrap-servers", KAFKA::getBootstrapServers);
    }

    static void registerDataSource(DynamicPropertyRegistry registry) {
        registry.add("spring.datasource.url", POSTGRES::getJdbcUrl);
        registry.add("spring.datasource.username", POSTGRES::getUsername);
        registry.add("spring.datasource.password", POSTGRES::getPassword);
    }
}
