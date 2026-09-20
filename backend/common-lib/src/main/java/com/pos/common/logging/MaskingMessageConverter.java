package com.pos.common.logging;

import ch.qos.logback.classic.pattern.ClassicConverter;
import ch.qos.logback.classic.spi.ILoggingEvent;

/**
 * Logback pattern converter that runs every formatted message through {@link LogMasker}.
 *
 * <p>Register it in {@code logback-spring.xml} and use {@code %maskedMsg} where {@code %msg} would
 * normally go:
 *
 * <pre>{@code
 * <conversionRule conversionWord="maskedMsg"
 *     converterClass="com.pos.common.logging.MaskingMessageConverter"/>
 * <pattern>%d %-5level [%X{correlationId}] %logger{36} - %maskedMsg%n</pattern>
 * }</pre>
 */
public class MaskingMessageConverter extends ClassicConverter {

    @Override
    public String convert(ILoggingEvent event) {
        return LogMasker.mask(event.getFormattedMessage());
    }
}
