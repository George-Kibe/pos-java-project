package com.pos.messaging;

import static org.assertj.core.api.Assertions.assertThat;

import java.time.Duration;

import org.junit.jupiter.api.Test;

import com.pos.messaging.outbox.OutboxProperties;

class OutboxPropertiesTest {

    @Test
    void defaultsSuitALaneThatMustFeelInstant() {
        OutboxProperties properties = new OutboxProperties();

        assertThat(properties.isEnabled()).isTrue();
        assertThat(properties.getPollInterval()).isEqualTo(Duration.ofSeconds(1));
        assertThat(properties.getBatchSize()).isEqualTo(100);
        assertThat(properties.getSendTimeout()).isEqualTo(Duration.ofSeconds(10));
        assertThat(properties.getMaxAttempts()).isEqualTo(10);
        assertThat(properties.getMaxBackoff()).isEqualTo(Duration.ofMinutes(5));
    }

    @Test
    void everySettingIsOverridable() {
        OutboxProperties properties = new OutboxProperties();
        properties.setEnabled(false);
        properties.setPollInterval(Duration.ofMillis(250));
        properties.setBatchSize(10);
        properties.setSendTimeout(Duration.ofSeconds(2));
        properties.setMaxAttempts(3);
        properties.setMaxBackoff(Duration.ofSeconds(30));

        assertThat(properties.isEnabled()).isFalse();
        assertThat(properties.getPollInterval()).isEqualTo(Duration.ofMillis(250));
        assertThat(properties.getBatchSize()).isEqualTo(10);
        assertThat(properties.getSendTimeout()).isEqualTo(Duration.ofSeconds(2));
        assertThat(properties.getMaxAttempts()).isEqualTo(3);
        assertThat(properties.getMaxBackoff()).isEqualTo(Duration.ofSeconds(30));
    }
}
