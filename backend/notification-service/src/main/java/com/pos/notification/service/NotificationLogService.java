package com.pos.notification.service;

import java.time.Instant;
import java.util.UUID;

import org.slf4j.Logger;
import org.slf4j.LoggerFactory;
import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Propagation;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.correlation.CorrelationId;
import com.pos.notification.domain.NotificationLog;
import com.pos.notification.domain.NotificationStatus;
import com.pos.notification.domain.NotificationType;
import com.pos.notification.repository.NotificationLogRepository;

import lombok.RequiredArgsConstructor;

/**
 * Writes the delivery log.
 *
 * <p>Every method commits in its own transaction, and that is the entire reason this is a separate
 * bean. A failed send throws so that Kafka redelivers - and a log row written in the listener's
 * transaction would be rolled back by that same exception. The table would then contain only
 * successes, and the one question it exists to answer, "why did this person never get their code",
 * would have no record at all.
 */
@Service
@RequiredArgsConstructor
public class NotificationLogService {

    private static final Logger log = LoggerFactory.getLogger(NotificationLogService.class);

    private static final int MAX_ERROR_LENGTH = 4000;

    private final NotificationLogRepository repository;

    /**
     * Opens or resumes the record for this event, counting the attempt.
     *
     * <p>Keyed on the event so a redelivery increments an existing row instead of creating a second
     * one - otherwise a message retried three times would look like three messages.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public UUID beginAttempt(
            String eventId,
            String eventType,
            NotificationType type,
            String recipient,
            String subject) {

        NotificationLog entry =
                repository
                        .findFirstByEventIdAndTypeOrderByCreatedAtDesc(eventId, type)
                        .orElseGet(NotificationLog::new);

        entry.setEventId(eventId);
        entry.setEventType(eventType);
        entry.setType(type);
        entry.setRecipient(recipient);
        entry.setSubject(subject);
        entry.setCorrelationId(CorrelationId.get());
        entry.setStatus(NotificationStatus.PENDING);
        entry.setAttempts(entry.getAttempts() + 1);

        return repository.save(entry).getId();
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markSent(UUID logId) {
        repository
                .findById(logId)
                .ifPresent(
                        entry -> {
                            entry.setStatus(NotificationStatus.SENT);
                            entry.setSentAt(Instant.now());
                            entry.setLastError(null);
                            repository.save(entry);
                        });
    }

    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markFailed(UUID logId, String error) {
        repository
                .findById(logId)
                .ifPresent(
                        entry -> {
                            entry.setStatus(NotificationStatus.FAILED);
                            entry.setLastError(truncate(error));
                            repository.save(entry);
                        });
    }

    /**
     * Records that retries are exhausted and the event has gone to the dead-letter topic.
     *
     * <p>Alertable, never silent: somebody is waiting for a message that is not coming.
     */
    @Transactional(propagation = Propagation.REQUIRES_NEW)
    public void markPermanentlyFailed(
            String eventId,
            String eventType,
            NotificationType type,
            String recipient,
            String error) {

        NotificationLog entry =
                repository
                        .findFirstByEventIdAndTypeOrderByCreatedAtDesc(eventId, type)
                        .orElseGet(NotificationLog::new);

        entry.setEventId(eventId);
        entry.setEventType(eventType);
        entry.setType(type);
        entry.setRecipient(recipient == null ? "unknown" : recipient);
        entry.setStatus(NotificationStatus.PERMANENTLY_FAILED);
        entry.setLastError(truncate(error));
        entry.setFailedAt(Instant.now());
        repository.save(entry);

        log.error(
                "Giving up on {} for event {}: {}. Nobody will receive this message.",
                type,
                eventId,
                error);
    }

    private static String truncate(String error) {
        if (error == null) {
            return null;
        }
        return error.length() > MAX_ERROR_LENGTH ? error.substring(0, MAX_ERROR_LENGTH) : error;
    }
}
