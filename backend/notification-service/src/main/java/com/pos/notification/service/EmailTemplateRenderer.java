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
    private final TemplateTexts.Source texts;

    /** With the wording that ships: for tests, and for rendering without a database. */
    public EmailTemplateRenderer(
            TemplateEngine htmlEngine,
            TemplateEngine textEngine,
            NotificationProperties properties) {
        this(htmlEngine, textEngine, properties, TemplateTexts::shipped);
    }

    @org.springframework.beans.factory.annotation.Autowired
    public EmailTemplateRenderer(
            @Qualifier("htmlEmailTemplateEngine") TemplateEngine htmlEngine,
            @Qualifier("textEmailTemplateEngine") TemplateEngine textEngine,
            NotificationProperties properties,
            TemplateTexts.Source texts) {
        this.htmlEngine = htmlEngine;
        this.textEngine = textEngine;
        this.properties = properties;
        this.texts = texts;
    }

    public EmailMessage render(
            NotificationType type,
            String recipient,
            String recipientName,
            Map<String, Object> model) {
        return render(type, recipient, recipientName, model, texts.textsFor(type));
    }

    /** Renders with the given wording - a draft being previewed, or the saved one. */
    public EmailMessage render(
            NotificationType type,
            String recipient,
            String recipientName,
            Map<String, Object> model,
            TemplateTexts wording) {

        Context context = new Context(Locale.ENGLISH);
        context.setVariables(model);
        // Branding is on every message, so callers never pass it.
        context.setVariable("brandName", properties.getBrandName());
        context.setVariable("supportEmail", properties.getSupportEmail());
        context.setVariable("appBaseUrl", properties.getAppBaseUrl());
        context.setVariable("recipientName", recipientName);
        context.setVariable("intro", blankToNull(wording.intro()));
        context.setVariable("closing", blankToNull(wording.closing()));

        String template = templateNameFor(type);
        return new EmailMessage(
                recipient,
                recipientName,
                wording.subject().replace("{brand}", properties.getBrandName()),
                htmlEngine.process(template, context),
                textEngine.process(template, context));
    }

    /**
     * The subject as it will go out. No code in a subject line, ever: subject lines appear on lock
     * screens and in notification previews, and are logged by mail gateways along the way - which
     * is why the wording is text alone, with no way to put the code in it.
     */
    public String subjectFor(NotificationType type) {
        return texts.textsFor(type).subject().replace("{brand}", properties.getBrandName());
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
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
