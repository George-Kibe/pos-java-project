package com.pos.notification.service;

/**
 * Sends a rendered message.
 *
 * <p>An interface with one implementation today, which earns its place: the provider is the part of
 * this service most likely to change. Local development uses an SMTP sink, production uses Gmail or
 * Workspace, and a transactional API such as Resend or SES would be a second implementation rather
 * than a rewrite of everything that calls this.
 */
public interface EmailSender {

    /**
     * @throws EmailDeliveryException when the message could not be handed to the provider; the
     *     caller lets it propagate so Kafka redelivers
     */
    void send(EmailMessage message);

    /** Thrown when delivery fails. Unchecked, so it propagates out of a Kafka listener. */
    class EmailDeliveryException extends RuntimeException {
        public EmailDeliveryException(String message, Throwable cause) {
            super(message, cause);
        }
    }
}
