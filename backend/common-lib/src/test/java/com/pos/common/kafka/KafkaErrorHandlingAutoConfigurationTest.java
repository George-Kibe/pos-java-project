package com.pos.common.kafka;

import static org.assertj.core.api.Assertions.assertThat;
import static org.mockito.Mockito.mock;

import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;
import org.springframework.boot.autoconfigure.AutoConfigurations;
import org.springframework.boot.test.context.runner.ApplicationContextRunner;
import org.springframework.kafka.core.KafkaOperations;
import org.springframework.kafka.listener.CommonErrorHandler;
import org.springframework.kafka.listener.CommonLoggingErrorHandler;
import org.springframework.kafka.listener.DefaultErrorHandler;

@DisplayName("Consumer failure policy")
class KafkaErrorHandlingAutoConfigurationTest {

    private final ApplicationContextRunner runner =
            new ApplicationContextRunner()
                    .withConfiguration(
                            AutoConfigurations.of(KafkaErrorHandlingAutoConfiguration.class));

    @Test
    @DisplayName("a service that can publish gets retry-then-dead-letter")
    void servicesGetTheSharedPolicy() {
        runner.withBean(KafkaOperations.class, () -> mock(KafkaOperations.class))
                .run(context -> assertThat(context).hasSingleBean(DefaultErrorHandler.class));
    }

    @Test
    @DisplayName("a service with a policy of its own keeps it")
    void aServicesOwnPolicyWins() {
        runner.withBean(KafkaOperations.class, () -> mock(KafkaOperations.class))
                .withBean(CommonErrorHandler.class, CommonLoggingErrorHandler::new)
                .run(
                        context -> {
                            assertThat(context).hasSingleBean(CommonErrorHandler.class);
                            assertThat(context).doesNotHaveBean(DefaultErrorHandler.class);
                        });
    }

    @Test
    @DisplayName("without Kafka there is nothing to handle")
    void noKafkaNoHandler() {
        runner.run(context -> assertThat(context).doesNotHaveBean(CommonErrorHandler.class));
    }
}
