package com.pos.notification.config;

import org.springframework.boot.context.properties.ConfigurationProperties;

import lombok.Getter;
import lombok.Setter;

/** Branding and links that appear in every message. */
@ConfigurationProperties(prefix = "pos.notification")
@Getter
@Setter
public class NotificationProperties {

    /** Appears in subjects and in the body. */
    private String brandName = "Realhive Group of Supermarkets POS";

    /** Where "contact us" points. */
    private String supportEmail = "support@example.com";

    /** Base URL of the web app, used to build links such as the password reset. */
    private String appBaseUrl = "http://localhost:3000";

    /**
     * Exposes a rendering preview at {@code /api/v1/notifications/preview}. Off by default: it
     * renders arbitrary templates with supplied values and belongs only on a developer's machine.
     */
    private boolean templatePreviewEnabled = false;

    /** The zone times are printed in, such as a receipt's date. Stored times are UTC. */
    private java.time.ZoneId displayTimeZone = java.time.ZoneId.of("Africa/Nairobi");

    /** How mail leaves this service. SMTP unless a test run says otherwise. */
    private MailTransport mailTransport = MailTransport.SMTP;

    /** Where {@link MailTransport#CAPTURE} writes messages; inside the container, never shared. */
    private String captureDirectory = "/tmp/pos-captured-mail";

    public enum MailTransport {
        /** Send through the configured SMTP server: Gmail in development, AWS SES in production. */
        SMTP,
        /**
         * Send nothing: write each message to a file in {@link #captureDirectory}. For browser
         * end-to-end runs, which register throwaway accounts whose codes must not be emailed to
         * made-up addresses, and read the code back with {@code docker exec}.
         */
        CAPTURE
    }
}
