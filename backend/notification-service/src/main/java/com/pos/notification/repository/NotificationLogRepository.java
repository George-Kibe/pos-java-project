package com.pos.notification.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.domain.Page;
import org.springframework.data.domain.Pageable;
import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.notification.domain.NotificationLog;
import com.pos.notification.domain.NotificationStatus;
import com.pos.notification.domain.NotificationType;

public interface NotificationLogRepository extends JpaRepository<NotificationLog, UUID> {

    /**
     * The existing record for an event, so a redelivery updates the attempt count rather than
     * creating a second row for the same message.
     */
    Optional<NotificationLog> findFirstByEventIdAndTypeOrderByCreatedAtDesc(
            String eventId, NotificationType type);

    Page<NotificationLog> findByStatusOrderByCreatedAtDesc(
            NotificationStatus status, Pageable pageable);

    long countByStatus(NotificationStatus status);
}
