package com.pos.common.kafka;

import org.springframework.boot.autoconfigure.AutoConfiguration;
import org.springframework.boot.autoconfigure.condition.ConditionalOnClass;
import org.springframework.boot.autoconfigure.condition.ConditionalOnMissingBean;
import org.springframework.context.annotation.Bean;
import org.springframework.core.task.SimpleAsyncTaskExecutor;
import org.springframework.kafka.config.ContainerCustomizer;
import org.springframework.kafka.listener.ConcurrentMessageListenerContainer;

/**
 * Runs Kafka listener containers on platform threads, even with {@code
 * spring.threads.virtual.enabled}.
 *
 * <p>On Java 21 a virtual thread that blocks inside a {@code synchronized} block pins its carrier,
 * and the Kafka consumer's group coordinator does most of its work - and its logging - inside
 * {@code synchronized} methods. During a rebalance every listener thread lands there at once,
 * blocks on the logging appender's lock, and pins a carrier each. With more listener threads than
 * CPUs (inventory runs fifteen on an eight-core host) every carrier is pinned, the thread holding
 * the lock can never be scheduled to release it, and the whole service stops: consumers, HTTP,
 * health checks - with no error and near-zero CPU.
 *
 * <p>Listener threads are few and long-lived, so virtual threads buy nothing here. Request handling
 * keeps them.
 */
@AutoConfiguration
@ConditionalOnClass(ConcurrentMessageListenerContainer.class)
public class KafkaListenerThreadsAutoConfiguration {

    @Bean
    @ConditionalOnMissingBean(name = "platformThreadKafkaListeners")
    public ContainerCustomizer<Object, Object, ConcurrentMessageListenerContainer<Object, Object>>
            platformThreadKafkaListeners() {
        return container -> {
            SimpleAsyncTaskExecutor executor =
                    new SimpleAsyncTaskExecutor(container.getBeanName() + "-");
            executor.setVirtualThreads(false);
            container.getContainerProperties().setListenerTaskExecutor(executor);
        };
    }
}
