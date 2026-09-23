package com.pos.notification.service;

/**
 * Sends a rendered message.
 *
 * <p>The provider is the part of this service most likely to change. SMTP serves development
 * (Gmail) and production (AWS SES's SMTP interface) alike; {@link CapturingEmailSender} stands in
 * for browser test runs; and a provider's own API would be another implementation rather than a
 * rewrite of everything that calls this.
 */
public interface EmailSender {

    /**
     * @throws EmailDeliveryException when the message could not be handed to the provider; the
     *     caller lets it propagate so Kafka redelivers
     */
    void send(EmailMessage message);

    /** Thrown when delivery fails. Unchecked, so it propagates out of a Kafka listener. */
    class EmailDeliveryException extends RuntimeException {
        @java.io.Serial private static final long serialVersionUID = 1L;

        public EmailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
