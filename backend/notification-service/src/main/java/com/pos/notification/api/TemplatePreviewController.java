package com.pos.notification.api;

import java.util.List;
import java.util.Map;

import org.springframework.boot.autoconfigure.condition.ConditionalOnProperty;
import org.springframework.http.MediaType;
import org.springframework.web.bind.annotation.GetMapping;
import org.springframework.web.bind.annotation.PathVariable;
import org.springframework.web.bind.annotation.RequestMapping;
import org.springframework.web.bind.annotation.RestController;

import com.pos.notification.config.NotificationProperties;
import com.pos.notification.domain.NotificationType;
import com.pos.notification.service.EmailMessage;
import com.pos.notification.service.EmailTemplateRenderer;

import io.swagger.v3.oas.annotations.Operation;
import io.swagger.v3.oas.annotations.tags.Tag;
import lombok.RequiredArgsConstructor;

/**
 * Renders a template with sample values, so a change to wording or layout can be seen without
 * triggering a real registration.
 *
 * <p>Off unless {@code pos.notification.template-preview-enabled} is true, and it is false by
 * default. It renders templates on demand, which is a developer convenience and not something that
 * should exist on a production host.
 *
 * <p>The sample values are obviously fake. Previewing with a real code would put a live credential
 * on a URL that ends up in browser history and access logs.
 */
@RestController
@RequestMapping("/api/v1/notifications/preview")
@RequiredArgsConstructor
@ConditionalOnProperty(
        prefix = "pos.notification",
        name = "template-preview-enabled",
        havingValue = "true")
@Tag(name = "Template preview (development only)")
public class TemplatePreviewController {

    private final EmailTemplateRenderer renderer;
    private final NotificationProperties properties;

    @GetMapping
    @Operation(summary = "List the templates that can be previewed")
    public Map<String, Object> list() {
        return Map.of(
                "templates",
                List.of(NotificationType.values()),
                "usage",
                "/api/v1/notifications/preview/{type}[?format=text]");
    }

    @GetMapping(value = "/{type}", produces = MediaType.TEXT_HTML_VALUE)
    @Operation(summary = "Render a template with sample values")
    public String preview(
            @PathVariable NotificationType type,
            @org.springframework.web.bind.annotation.RequestParam(defaultValue = "html")
                    String format) {

        EmailMessage message =
                renderer.render(type, "someone@example.com", "Ada Lovelace", sampleModel(type));

        if ("text".equalsIgnoreCase(format)) {
            // Wrapped so a browser shows the plain-text body verbatim rather than collapsing it.
            return "<pre>"
                    + org.springframework.web.util.HtmlUtils.htmlEscape(message.text())
                    + "</pre>";
        }
        return message.html();
    }

    private Map<String, Object> sampleModel(NotificationType type) {
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
                    com.pos.notification.service.ReceiptEmailModel.from(
                            com.pos.notification.service.ReceiptEmailModel.sample(),
                            properties.getDisplayTimeZone());
        };
    }
}
