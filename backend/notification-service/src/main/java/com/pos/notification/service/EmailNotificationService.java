package com.pos.notification.service;

import java.util.Map;
import java.util.UUID;

import org.springframework.stereotype.Service;

import com.pos.notification.domain.NotificationType;

import lombok.RequiredArgsConstructor;

/**
 * Renders a message, sends it, and records what happened.
 *
 * <p>Failure is rethrown on purpose. The listener's transaction rolls back, the idempotency marker
 * goes with it, and Kafka redelivers - so a transient SMTP problem resolves itself. The log entry
 * survives regardless, because {@link NotificationLogService} commits separately.
 */
@Service
@RequiredArgsConstructor
public class EmailNotificationService {

    private final EmailTemplateRenderer renderer;
    private final EmailSender sender;
    private final NotificationLogService logService;

    public void send(
            String eventId,
            String eventType,
            NotificationType type,
            String recipient,
            String recipientName,
            Map<String, Object> model) {

        EmailMessage message = renderer.render(type, recipient, recipientName, model);
        UUID logId =
                logService.beginAttempt(eventId, eventType, type, recipient, message.subject());

        try {
            sender.send(message);
            logService.markSent(logId);
        } catch (RuntimeException e) {
            logService.markFailed(logId, e.getMessage());
            throw e;
        }
    }
}
