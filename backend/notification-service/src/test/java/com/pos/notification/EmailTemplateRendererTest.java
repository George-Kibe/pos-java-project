package com.pos.notification;

import static org.assertj.core.api.Assertions.assertThat;

import java.util.List;
import java.util.Map;

import org.junit.jupiter.api.BeforeEach;
import org.junit.jupiter.api.DisplayName;
import org.junit.jupiter.api.Test;

import com.pos.notification.config.NotificationProperties;
import com.pos.notification.config.ThymeleafEmailConfig;
import com.pos.notification.domain.NotificationType;
import com.pos.notification.service.EmailMessage;
import com.pos.notification.service.EmailTemplateRenderer;

/** Rendering, without a broker or a mail server in the way. */
class EmailTemplateRendererTest {

    private EmailTemplateRenderer renderer;

    @BeforeEach
    void setUp() {
        ThymeleafEmailConfig config = new ThymeleafEmailConfig();
        NotificationProperties properties = new NotificationProperties();
        properties.setBrandName("Test Supermarket");
        properties.setSupportEmail("help@test.local");
        properties.setAppBaseUrl("https://pos.test");

        renderer =
                new EmailTemplateRenderer(
                        config.htmlEmailTemplateEngine(),
                        config.textEmailTemplateEngine(),
                        properties);
    }

    @Test
    @DisplayName("both bodies carry the code, and the subject does not")
    void otpRendersIntoBothBodies() {
        EmailMessage message =
                renderer.render(
                        NotificationType.OTP_CODE,
                        "ada@example.com",
                        "Ada",
                        Map.of(
                                "otpCode",
                                "424242",
                                "expiresInMinutes",
                                10L,
                                "purpose",
                                "REGISTRATION"));

        assertThat(message.subject()).isEqualTo("Your Test Supermarket verification code");
        assertThat(message.subject()).doesNotContain("424242");

        assertThat(message.html()).contains("424242").contains("Ada").contains("10");
        assertThat(message.text()).contains("424242").contains("Ada").contains("10");

        // The text part must genuinely be text, not HTML with the tags left in.
        assertThat(message.text()).doesNotContain("<html").doesNotContain("<table");
        assertThat(message.html()).contains("<!DOCTYPE html>");
    }

    @Test
    void brandingIsAppliedWithoutTheCallerPassingIt() {
        EmailMessage message =
                renderer.render(
                        NotificationType.WELCOME,
                        "new@example.com",
                        "Newcomer",
                        Map.of("roles", List.of("CASHIER")));

        assertThat(message.subject()).isEqualTo("Welcome to Test Supermarket");
        assertThat(message.html()).contains("Test Supermarket").contains("help@test.local");
        assertThat(message.html()).contains("https://pos.test");
        assertThat(message.text()).contains("Test Supermarket");
    }

    @Test
    @DisplayName("the reset link appears as a button and as text, for clients that strip buttons")
    void passwordResetShowsTheLinkTwice() {
        EmailMessage message =
                renderer.render(
                        NotificationType.PASSWORD_RESET,
                        "forgot@example.com",
                        "Forgot",
                        Map.of(
                                "resetUrl",
                                "https://pos.test/reset-password?token=abc",
                                "expiresInMinutes",
                                30L));

        assertThat(message.subject()).isEqualTo("Reset your Test Supermarket password");
        assertThat(message.html())
                .containsSubsequence("Reset password", "Or paste this into your browser");
        assertThat(message.text()).contains("https://pos.test/reset-password?token=abc");
        assertThat(message.text()).contains("30");
    }

    @Test
    void everyTypeHasASubjectAndRenders() {
        for (NotificationType type : NotificationType.values()) {
            assertThat(renderer.subjectFor(type)).isNotBlank().contains("Test Supermarket");
        }
    }
}
