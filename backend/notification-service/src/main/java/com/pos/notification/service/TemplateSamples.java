package com.pos.notification.service;

import java.util.List;
import java.util.Map;

import org.springframework.stereotype.Component;

import com.pos.notification.config.NotificationProperties;
import com.pos.notification.domain.NotificationType;

/**
 * Obviously fake values to render a template with, for previews. Never a real code: a preview shows
 * on screens and lands in browser history.
 */
@Component
public class TemplateSamples {

    private final NotificationProperties properties;

    public TemplateSamples(NotificationProperties properties) {
        this.properties = properties;
    }

    public Map<String, Object> modelFor(NotificationType type) {
        return switch (type) {
            case OTP_CODE ->
                    Map.of("otpCode", "000000", "expiresInMinutes", 10L, "purpose", "REGISTRATION");
            case WELCOME -> Map.of("roles", List.of("CASHIER"));
            case PASSWORD_RESET ->
                    Map.of(
                            "resetUrl",
                            properties.getAppBaseUrl() + "/reset-password?token=sample-token",
                            "expiresInMinutes",
                            30L);
            case RECEIPT ->
                    ReceiptEmailModel.from(
                            ReceiptEmailModel.sample(), properties.getDisplayTimeZone());
        };
    }
}
