package com.pos.notification.domain;

import jakarta.persistence.Column;
import jakarta.persistence.Entity;
import jakarta.persistence.EnumType;
import jakarta.persistence.Enumerated;
import jakarta.persistence.Table;

import com.pos.common.persistence.BaseEntity;

import lombok.Getter;
import lombok.NoArgsConstructor;
import lombok.Setter;

/** The wording of one kind of email, as changed from what ships. */
@Entity
@Table(name = "template_texts")
@Getter
@Setter
@NoArgsConstructor
public class TemplateText extends BaseEntity {

    @Enumerated(EnumType.STRING)
    @Column(nullable = false, length = 40, updatable = false)
    private NotificationType type;

    @Column(nullable = false, length = 200)
    private String subject;

    @Column(length = 1000)
    private String intro;

    @Column(length = 1000)
    private String closing;

    public TemplateText(NotificationType type) {
        this.type = type;
    }
}
