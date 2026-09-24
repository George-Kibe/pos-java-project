package com.pos.notification.service;

import java.util.Locale;
import java.util.Map;

import org.springframework.beans.factory.annotation.Qualifier;
import org.springframework.stereotype.Component;
import org.thymeleaf.TemplateEngine;
import org.thymeleaf.context.Context;

import com.pos.notification.config.NotificationProperties;
import com.pos.notification.domain.NotificationType;

/** Renders both bodies of a message from one model. */
@Component
public class EmailTemplateRenderer {

    private final TemplateEngine htmlEngine;
    private final TemplateEngine textEngine;
    private final NotificationProperties properties;

    public EmailTemplateRenderer(
            @Qualifier("htmlEmailTemplateEngine") TemplateEngine htmlEngine,
            @Qualifier("textEmailTemplateEngine") TemplateEngine textEngine,
            NotificationProperties properties) {
        this.htmlEngine = htmlEngine;
        this.textEngine = textEngine;
        this.properties = properties;
    }

    public EmailMessage render(
            NotificationType type,
            String recipient,
            String recipientName,
            Map<String, Object> model) {

        Context context = new Context(Locale.ENGLISH);
        context.setVariables(model);
        // Branding is on every message, so callers never pass it.
        context.setVariable("brandName", properties.getBrandName());
        context.setVariable("supportEmail", properties.getSupportEmail());
        context.setVariable("appBaseUrl", properties.getAppBaseUrl());
        context.setVariable("recipientName", recipientName);

        String template = templateNameFor(type);
        return new EmailMessage(
                recipient,
                recipientName,
                subjectFor(type),
                htmlEngine.process(template, context),
                textEngine.process(template, context));
    }

    public String subjectFor(NotificationType type) {
        return switch (type) {
            // No code in the subject line: subject lines appear on lock screens and in
            // notification previews, and are logged by mail gateways along the way.
            case OTP_CODE -> "Your %s verification code".formatted(properties.getBrandName());
            case WELCOME -> "Welcome to %s".formatted(properties.getBrandName());
            case PASSWORD_RESET -> "Reset your %s password".formatted(properties.getBrandName());
            case RECEIPT -> "Your receipt from %s".formatted(properties.getBrandName());
        };
    }

    private static String templateNameFor(NotificationType type) {
        return switch (type) {
            case OTP_CODE -> "otp-code";
            case WELCOME -> "welcome";
            case PASSWORD_RESET -> "password-reset";
            case RECEIPT -> "receipt";
        };
    }
}
