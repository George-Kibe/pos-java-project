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

    @Test
    @DisplayName("a receipt prints money to cents, weighed quantities in full, and no empty rows")
    void receiptRendersWhatWasCharged() {
        var model =
                com.pos.notification.service.ReceiptEmailModel.from(
                        com.pos.notification.service.ReceiptEmailModel.sample(),
                        java.time.ZoneId.of("Africa/Nairobi"));
        EmailMessage message =
                renderer.render(NotificationType.RECEIPT, "someone@example.com", null, model);

        assertThat(message.subject()).isEqualTo("Your receipt from Test Supermarket");
        for (String body : List.of(message.html(), message.text())) {
            assertThat(body)
                    .contains("R-000000")
                    // 09:15 UTC is 12:15 in Nairobi.
                    .contains("2 Jan 2026, 12:15")
                    .contains("0.735")
                    .contains("218.20")
                    .contains("16%")
                    .contains("17.93")
                    .contains("Change from")
                    .contains("300.00")
                    .contains("81.80")
                    // The branch's own text, above and below the sale.
                    .contains("Open every day, 7am to 10pm")
                    .contains("Goods may be returned within 7 days with this receipt.")
                    .contains("Thank you for shopping with us.")
                    .contains("Moi Avenue, Nairobi")
                    .contains("P000000000X")
                    // No discount was given, so none is shown; no name, so no greeting.
                    .doesNotContain("Discounts")
                    .doesNotContain("Hello");
        }
        assertThat(message.text()).doesNotContain("<td");
    }

    @Test
    @DisplayName("receipt figures round half up at the display step")
    void receiptFiguresRoundHalfUp() {
        var model =
                com.pos.notification.service.ReceiptEmailModel.from(
                        new com.pos.events.sales.ReceiptEmailRequestedPayload(
                                java.util.UUID.randomUUID(),
                                java.util.UUID.randomUUID(),
                                "R-000009",
                                java.util.UUID.randomUUID(),
                                "someone@example.com",
                                "Ada",
                                java.time.Instant.parse("2026-01-02T09:15:00Z"),
                                "KES",
                                List.of(),
                                List.of(),
                                List.of(),
                                new java.math.BigDecimal("0.0000"),
                                new java.math.BigDecimal("1.0050"),
                                new java.math.BigDecimal("1234.5650"),
                                null,
                                null,
                                null),
                        java.time.ZoneOffset.UTC);

        assertThat(model.get("grandTotal")).isEqualTo("1,234.57");
        assertThat(model.get("taxTotal")).isEqualTo("1.01");
        assertThat(model.get("changeGiven")).isNull();
        assertThat(model.get("discountTotal")).isNull();
    }
}
