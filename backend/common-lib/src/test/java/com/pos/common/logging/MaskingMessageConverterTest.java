package com.pos.common.logging;

import static org.assertj.core.api.Assertions.assertThat;

import org.junit.jupiter.api.Test;
import org.slf4j.LoggerFactory;

import ch.qos.logback.classic.Level;
import ch.qos.logback.classic.Logger;
import ch.qos.logback.classic.LoggerContext;
import ch.qos.logback.classic.spi.LoggingEvent;

class MaskingMessageConverterTest {

    @Test
    void masksTheFormattedMessageIncludingInterpolatedArguments() {
        LoggerContext context = (LoggerContext) LoggerFactory.getILoggerFactory();
        Logger logger = context.getLogger(MaskingMessageConverterTest.class);

        // The danger case: the secret arrives as a parameter, so it is only visible once the
        // message has been formatted.
        LoggingEvent event =
                new LoggingEvent(
                        "fqcn",
                        logger,
                        Level.INFO,
                        "login for {} password={}",
                        null,
                        new Object[] {"ada", "hunter2"});

        String rendered = new MaskingMessageConverter().convert(event);

        assertThat(rendered).contains("ada").contains("password=***").doesNotContain("hunter2");
    }
}
