package com.pos.notification.service;

import java.util.Arrays;
import java.util.List;

import org.springframework.stereotype.Service;
import org.springframework.transaction.annotation.Transactional;

import com.pos.common.error.Errors;
import com.pos.notification.domain.NotificationType;
import com.pos.notification.domain.TemplateText;
import com.pos.notification.repository.TemplateTextRepository;

import lombok.RequiredArgsConstructor;

/**
 * The wording of each email: what has been saved over the shipped text, or the shipped text. Read
 * for every email sent, so a change applies to the next one.
 */
@Service
@RequiredArgsConstructor
@Transactional(readOnly = true)
public class TemplateTextService implements TemplateTexts.Source {

    private final TemplateTextRepository texts;

    public record Wording(NotificationType type, TemplateTexts texts, boolean changed) {}

    @Override
    public TemplateTexts textsFor(NotificationType type) {
        return texts.findByType(type)
                .map(
                        saved ->
                                new TemplateTexts(
                                        saved.getSubject(), saved.getIntro(), saved.getClosing()))
                .orElseGet(() -> TemplateTexts.shipped(type));
    }

    public List<Wording> all() {
        return Arrays.stream(NotificationType.values())
                .map(type -> new Wording(type, textsFor(type), texts.findByType(type).isPresent()))
                .toList();
    }

    /** Checked as a save would check it; for previews and saves alike. */
    public static TemplateTexts checked(String subject, String intro, String closing) {
        if (subject == null || subject.isBlank()) {
            throw new Errors.BadRequestException(
                    "template.subject_required", "An email needs a subject.");
        }
        if (subject.contains("\n") || subject.contains("\r")) {
            throw new Errors.BadRequestException(
                    "template.subject_one_line", "A subject is one line.");
        }
        return new TemplateTexts(subject.strip(), blankToNull(intro), blankToNull(closing));
    }

    @Transactional
    public TemplateTexts save(NotificationType type, String subject, String intro, String closing) {
        TemplateTexts wording = checked(subject, intro, closing);
        TemplateText row = texts.findByType(type).orElseGet(() -> new TemplateText(type));
        row.setSubject(wording.subject());
        row.setIntro(wording.intro());
        row.setClosing(wording.closing());
        texts.save(row);
        return wording;
    }

    /** Back to the wording that ships. The wording is configuration, not a record, so it goes. */
    @Transactional
    public TemplateTexts reset(NotificationType type) {
        texts.findByType(type).ifPresent(texts::delete);
        return TemplateTexts.shipped(type);
    }

    private static String blankToNull(String value) {
        return value == null || value.isBlank() ? null : value.strip();
    }
}
