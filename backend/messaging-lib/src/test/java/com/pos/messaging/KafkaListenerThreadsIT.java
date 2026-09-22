package com.pos.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.concurrent.CompletableFuture;
import java.util.concurrent.TimeUnit;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.beans.factory.annotation.Autowired;
import org.springframework.boot.test.context.SpringBootTest;
import org.springframework.boot.test.context.TestConfiguration;
import org.springframework.kafka.annotation.KafkaListener;
import org.springframework.kafka.core.KafkaTemplate;
import org.springframework.test.context.DynamicPropertyRegistry;
import org.springframework.test.context.DynamicPropertySource;

/**
 * A Kafka listener runs on a platform thread even when the application runs on virtual threads.
 *
 * <p>Inventory once stopped dead on a live stack: fifteen listener threads pinned eight carriers
 * inside the consumer coordinator's {@code synchronized} code during a rebalance, and nothing -
 * consumers, HTTP, health - ran again. See common-lib's {@link
 * com.pos.common.kafka.KafkaListenerThreadsAutoConfiguration}.
 */
@SpringBootTest(
        classes = {MessagingTestApplication.class, KafkaListenerThreadsIT.Listener.class},
        properties = {
            "spring.threads.virtual.enabled=true",
            "spring.kafka.consumer.auto-offset-reset=earliest",
            "spring.kafka.consumer.key-deserializer="
                    + "org.apache.kafka.common.serialization.StringDeserializer",
            "spring.kafka.consumer.value-deserializer="
                    + "org.apache.kafka.common.serialization.StringDeserializer"
        })
class KafkaListenerThreadsIT {

    static final String TOPIC = "pos.test.listener-threads.v1";

    @DynamicPropertySource
    static void infrastructure(DynamicPropertyRegistry registry) {
        MessagingContainers.registerDataSource(registry);
        MessagingContainers.registerKafka(registry);
    }

    @TestConfiguration
    static class Listener {

        final CompletableFuture<Thread> receivedOn = new CompletableFuture<>();

        @KafkaListener(topics = TOPIC, groupId = "listener-threads-test")
        void receive(String message) {
            receivedOn.complete(Thread.currentThread());
        }
    }

    @Autowired private KafkaTemplate<String, String> kafka;
    @Autowired private Listener listener;

    @Test
    @DisplayName("with virtual threads on, a listener still runs on a platform thread")
    void listenersRunOnPlatformThreads() throws Exception {
        kafka.send(TOPIC, "key", "hello").get(10, TimeUnit.SECONDS);

        Thread thread = listener.receivedOn.get(30, TimeUnit.SECONDS);

        assertThat(thread.isVirtual()).isFalse();
        assertThat(thread.getName()).startsWith("org.springframework.kafka");
    }
}
