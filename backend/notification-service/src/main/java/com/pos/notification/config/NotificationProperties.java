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
    private String brandName = "Supermarket POS";

    /** Where "contact us" points. */
    private String supportEmail = "support@example.com";

    /** Base URL of the web app, used to build links such as the password reset. */
    private String appBaseUrl = "http://localhost:3000";

    /**
     * Exposes a rendering preview at {@code /api/v1/notifications/preview}. Off by default: it
     * renders arbitrary templates with supplied values and belongs only on a developer's machine.
     */
    private boolean templatePreviewEnabled = false;
}
