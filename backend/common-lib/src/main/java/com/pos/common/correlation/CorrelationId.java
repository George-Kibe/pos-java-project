package com.pos.common.correlation;

import java.util.UUID;

import org.slf4j.MDC;

/**
 * The identifier that follows one logical operation across every service, log line and Kafka event.
 *
 * <p>Held in SLF4J's MDC so it lands in every log line without being threaded through method
 * signatures. Anything that hands work to another thread or to a message must copy it across
 * explicitly - the MDC does not follow a thread pool hand-off on its own.
 */
public final class CorrelationId {

    private CorrelationId() {}

    /** Inbound and outbound HTTP header. */
    public static final String HEADER = "X-Correlation-Id";

    /** MDC key, referenced by the logging configuration. */
    public static final String MDC_KEY = "correlationId";

    public static String get() {
        return MDC.get(MDC_KEY);
    }

    /** The current id, or a freshly generated one if there is none. */
    public static String getOrCreate() {
        String existing = MDC.get(MDC_KEY);
        if (existing == null || existing.isBlank()) {
            existing = generate();
            MDC.put(MDC_KEY, existing);
        }
        return existing;
    }

    public static void set(String correlationId) {
        if (correlationId == null || correlationId.isBlank()) {
            MDC.remove(MDC_KEY);
        } else {
            MDC.put(MDC_KEY, correlationId);
        }
    }

    public static void clear() {
        MDC.remove(MDC_KEY);
    }

    public static String generate() {
        return UUID.randomUUID().toString();
    }

    /** Runs {@code task} with the given correlation id, restoring the previous one afterwards. */
    public static void with(String correlationId, Runnable task) {
        String previous = MDC.get(MDC_KEY);
        try {
            set(correlationId);
            task.run();
        } finally {
            set(previous);
        }
    }
}
