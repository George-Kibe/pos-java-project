package com.pos.notification.repository;

import java.util.Optional;
import java.util.UUID;

import org.springframework.data.jpa.repository.JpaRepository;

import com.pos.notification.domain.NotificationType;
import com.pos.notification.domain.TemplateText;

public interface TemplateTextRepository extends JpaRepository<TemplateText, UUID> {

    Optional<TemplateText> findByType(NotificationType type);
}
