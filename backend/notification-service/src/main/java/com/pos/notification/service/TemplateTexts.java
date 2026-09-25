package com.pos.notification.service;

import com.pos.notification.domain.NotificationType;

/**
 * The words around what an email carries: its subject (where {@code {brand}} becomes the brand's
 * name), the paragraph that opens it and the one that closes it.
 */
public record TemplateTexts(String subject, String intro, String closing) {

    /** The wording that ships, and what an email says until someone changes it. */
    public static TemplateTexts shipped(NotificationType type) {
        return switch (type) {
            case OTP_CODE ->
                    new TemplateTexts(
                            "Your {brand} verification code",
                            "Use this code to verify your email address:",
                            "If you did not request this, you can ignore this email - nobody can"
                                    + " use the code without it.");
            case WELCOME ->
                    new TemplateTexts(
                            "Welcome to {brand}",
                            "Your email is verified and your account is active.",
                            "A manager needs to assign your role before you can start work. Until"
                                    + " then you can sign in, but you will not see anything yet -"
                                    + " that is expected.");
            case PASSWORD_RESET ->
                    new TemplateTexts(
                            "Reset your {brand} password",
                            "Someone asked to reset the password on your account. If that was you,"
                                    + " use the link below.",
                            "If you did not ask for this, ignore this email. Your password has not"
                                    + " changed, and nothing happens unless the link is used.");
            case RECEIPT ->
                    new TemplateTexts(
                            "Your receipt from {brand}", null, "Keep this email for returns.");
        };
    }

    /** Where the wording comes from: what ships, or what has been saved over it. */
    public interface Source {
        TemplateTexts textsFor(NotificationType type);
    }
}
